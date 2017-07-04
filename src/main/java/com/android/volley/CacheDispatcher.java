/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley;

import android.os.Process;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing cache triage on a queue of requests.
 * 一个线程，用于调度处理走缓存的请求。启动后会不断从缓存请求队列中取请求处理，队列为空则等待，
 * 请求处理结束则将结果传递给ResponseDelivery去执行后续处理。
 * 当结果未缓存过、缓存失效或缓存需要刷新的情况下，该请求都需要重新进入NetworkDispatcher去调度处理。
 *
 * Requests added to the specified cache queue are resolved from cache.
 * Any deliverable response is posted back to the caller via a
 * {@link ResponseDelivery}.  Cache misses and responses that require
 * refresh are enqueued on the specified network queue for processing
 * by a {@link NetworkDispatcher}.
 */
public class CacheDispatcher extends Thread {

    private static final boolean DEBUG = VolleyLog.DEBUG;

    /** The queue of requests coming in for triage. */
    private final BlockingQueue<Request<?>> mCacheQueue;

    /** The queue of requests going out to the network. */
    private final BlockingQueue<Request<?>> mNetworkQueue;

    /** The cache to read from. */
    private final Cache mCache;

    /** For posting responses. */
    private final ResponseDelivery mDelivery;

    /** Used for telling us to die. */
    private volatile boolean mQuit = false;

    /**
     * Creates a new cache triage dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     *
     * @param cacheQueue Queue of incoming requests for triage
     * @param networkQueue Queue to post requests that require network to
     * @param cache Cache interface to use for resolution
     * @param delivery Delivery interface to use for posting responses
     */
    public CacheDispatcher(
            BlockingQueue<Request<?>> cacheQueue, BlockingQueue<Request<?>> networkQueue,
            Cache cache, ResponseDelivery delivery) {
        mCacheQueue = cacheQueue;
        mNetworkQueue = networkQueue;
        mCache = cache;
        mDelivery = delivery;
    }

    /**
     * Forces this dispatcher to quit immediately.  If any requests are still in
     * the queue, they are not guaranteed to be processed.
     */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    @Override
    public void run() {
        if (DEBUG) VolleyLog.v("start new dispatcher");
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // Make a blocking call to initialize the cache.
        // mCache就是进行缓存的核心类，请求都是通过该类缓存在指定的地方，这里是对其进行初始化工作。
        // 这里默认为DiskBasedCache，原理是将数据以流的形式写入到磁盘文件。
        mCache.initialize();

        Request<?> request;
        // while(true)循环，说明缓存线程始终是在运行的
        while (true) {
            // release previous request object to avoid leaking request object when mQueue is drained.
            request = null;
            try {
                // Take a request from the queue.
                request = mCacheQueue.take();
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }
            try {
                request.addMarker("cache-queue-take");

                // If the request has been canceled, don't bother dispatching it.
                if (request.isCanceled()) {
                    request.finish("cache-discard-canceled");
                    continue;
                }

                // Attempt to retrieve this item from cache.
                // 通过CacheKey判断下是否请求已经在缓存中，如果在的话则从缓存当中取出响应结果
                // 请求的缓存都是以Cache接口的内部类Entry缓存起来的
                // Entry是一个很简单的实体类，存储的是和请求判断是否过期相关的属性，
                // 那这里的缓存过程是在哪里进行的呢？这很容易猜到，在网络请求成功后。
                Cache.Entry entry = mCache.get(request.getCacheKey());
                if (entry == null) {
                    // 如果为空的话则把这条请求加入到网络请求队列中
                    request.addMarker("cache-miss");
                    // Cache miss; send off to the network dispatcher.
                    mNetworkQueue.put(request);
                    continue;
                }

                // If it is completely expired, just send it to the network.
                // 如果不为空的话再判断该缓存是否已过期
                // （根据前面在HttpHeaderParser解析的响应头算出来的属性ttl与当前时间的比较）
                if (entry.isExpired()) {
                    // 如果已经过期了则同样把这条请求加入到网络请求队列中
                    request.addMarker("cache-hit-expired");
                    // 将该Entry传递给request，然后将请求重新添加到请求队列中，重新请求。
                    request.setCacheEntry(entry);
                    mNetworkQueue.put(request);
                    continue;
                }

                // We have a cache hit; parse its data for delivery back to the request.
                // 认为不需要重发网络请求，直接使用缓存中的数据即可
                request.addMarker("cache-hit");
                // 调用Request的parseNetworkResponse()方法来对数据进行解析，再往后就是将解析出来的数据进行回调了
                Response<?> response = request.parseNetworkResponse(
                        new NetworkResponse(entry.data, entry.responseHeaders));
                request.addMarker("cache-hit-parsed");

                // 验证新鲜度entry.refreshNeeded()（softTtl与当前时间的比较），
                // 不用验证新鲜度则直接将缓存的数据传递到客户端线程，需要刷新则还是将将该Entry传递给request，
                // 然后请求添加到请求队列去验证响应数据新鲜度。
                // 主要还是判断是否过期
                if (!entry.refreshNeeded()) {
                    // Completely unexpired cache hit. Just deliver the response.
                    // 如果没有过期则直接通过mDelivery.postResponse转发，然后回调到UI线程
                    mDelivery.postResponse(request, response);
                } else {
                    // Soft-expired cache hit. We can deliver the cached response,
                    // but we need to also send the request to the network for
                    // refreshing.
                    request.addMarker("cache-hit-refresh-needed");
                    request.setCacheEntry(entry);

                    // Mark the response as intermediate.
                    response.intermediate = true;

                    // Post the intermediate response back to the user and have
                    // the delivery then forward the request along to the network.
                    // 如果ttl不合法，回调完成后，还会将该请求加入mNetworkQueue
                    final Request<?> finalRequest = request;
                    mDelivery.postResponse(request, response, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                mNetworkQueue.put(finalRequest);
                            } catch (InterruptedException e) {
                                // Not much we can do about this.
                            }
                        }
                    });
                }
            } catch (Exception e) {
                VolleyLog.e(e, "Unhandled exception %s", e.toString());
            }
        }
    }
}
