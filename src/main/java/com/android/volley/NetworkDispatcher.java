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

import android.annotation.TargetApi;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing network dispatch from a queue of requests.
 * 一个线程，用于调度处理走网络的请求。
 * 启动后会不断从网络请求队列中取请求处理，队列为空则等待，
 * 请求处理结束则将结果传递给ResponseDelivery去执行后续处理，并判断结果是否要进行缓存。
 *
 * Requests added to the specified queue are processed from the network via a
 * specified {@link Network} interface. Responses are committed to cache, if
 * eligible, using a specified {@link Cache} interface. Valid responses and
 * errors are posted back to the caller via a {@link ResponseDelivery}.
 */
public class NetworkDispatcher extends Thread {
    /** The queue of requests to service. */
    private final BlockingQueue<Request<?>> mQueue;
    /** The network interface for processing requests. */
    private final Network mNetwork;
    /** The cache to write to. */
    private final Cache mCache;
    /** For posting responses and errors. */
    private final ResponseDelivery mDelivery;
    /** Used for telling us to die. */
    private volatile boolean mQuit = false;

    /**
     * Creates a new network dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     *
     * @param queue Queue of incoming requests for triage
     * @param network Network interface to use for performing requests
     * @param cache Cache interface to use for writing responses to cache
     * @param delivery Delivery interface to use for posting responses
     */
    public NetworkDispatcher(BlockingQueue<Request<?>> queue,
            Network network, Cache cache,
            ResponseDelivery delivery) {
        mQueue = queue;
        mNetwork = network;
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

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void addTrafficStatsTag(Request<?> request) {
        // Tag the request (if API >= 14)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            TrafficStats.setThreadStatsTag(request.getTrafficStatsTag());
        }
    }

    @Override
    public void run() {
        // 首先会将线程的优先级设置为比普通线程低一点，为的是尽量确保线程不影响UI的体验。
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Request<?> request;
        // while(true)循环，说明网络请求线程也是在不断运行的
        while (true) {
            long startTimeMs = SystemClock.elapsedRealtime();
            // release previous request object to avoid leaking request object when mQueue is drained.
            request = null;
            try {
                // Take a request from the queue.
                // 不断从mQueue（就是RequestQueue传进来的优先级阻塞队列）中取出Request
                request = mQueue.take();
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }

            try {
                request.addMarker("network-queue-take");

                // If the request was cancelled already, do not perform the
                // network request.
                if (request.isCanceled()) {
                    // 如果取消则对请求调用finish并重新循环取下一个请求
                    // finish的处理就是将请求从RequestQueue的mCurrentRequests和将该请求移除
                    // （这里取消请求，往往运用在和Activity生命周期联动上，
                    // 在Activity的destroy方法取消掉该Activity的所有请求。主要是防止内存泄漏）
                    request.finish("network-discard-cancelled");
                    continue;
                }

                addTrafficStatsTag(request);

                // Perform the network request.
                // 会调用Network的performRequest()方法来去发送网络请求，
                // 而Network是一个接口，这里具体的实现是BasicNetwork，我们来看下它的performRequest()方法
                NetworkResponse networkResponse = mNetwork.performRequest(request);
                request.addMarker("network-http-complete");

                // If the server returned 304 AND we delivered a response already,
                // we're done -- don't deliver a second identical response.
                if (networkResponse.notModified && request.hasHadResponseDelivered()) {
                    request.finish("not-modified");
                    continue;
                }

                // Parse the response here on the worker thread.
                // 调用Request的parseNetworkResponse()方法来解析NetworkResponse中的数据，
                // 以及将数据写入到缓存
                // 这个方法的实现是交给Request的子类来完成的,不同种类的Request解析的方式肯定不同,自定义Request这个方法就是必须要重写的
                // 将二级制数转化为对应的所需要的数据形式
                // 注：因为我们NetworkResponse返回都是服务器返回的一些数据，而不同的请求对应的返回结果肯定不同，比如我们的ImageRequest，解析出来是个bitmap，
                // 所以我们的解析代码是在Request里面;这里大家想想，如果我们自定义Request，该方法是不是会重写呢？详情请戳Android Volley 之自定义Request
                Response<?> response = request.parseNetworkResponse(networkResponse);
                request.addMarker("network-parse-complete");

                // Write to cache if applicable.
                // TODO: Only update cache metadata instead of entire record for 304s.
                // 如果请求是需要缓存的，就将请求相关数据缓存在磁盘中，mCache默认是DiskBasedCache。
                if (request.shouldCache() && response.cacheEntry != null) {
                    mCache.put(request.getCacheKey(), response.cacheEntry);
                    request.addMarker("network-cache-written");
                }

                // Post the response back.
                request.markDelivered();
                // 调用ExecutorDelivery的postResponse()方法来回调解析出的数据,将请求结果传递到主线程
                // 在我们使用Volley的newRequestQueue方法创建一个RequestQueue的时候，
                // 就会调用到RequestQueue的这个构造方法,创建了一个ExecutorDelivery对象，
                // 并且传入了Looper为主线程Looper的Handler对象
                mDelivery.postResponse(request, response);
            } catch (VolleyError volleyError) {
                volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                parseAndDeliverNetworkError(request, volleyError);
            } catch (Exception e) {
                VolleyLog.e(e, "Unhandled exception %s", e.toString());
                VolleyError volleyError = new VolleyError(e);
                volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                mDelivery.postError(request, volleyError);
            }
        }
    }

    private void parseAndDeliverNetworkError(Request<?> request, VolleyError error) {
        error = request.parseNetworkError(error);
        mDelivery.postError(request, error);
    }
}
