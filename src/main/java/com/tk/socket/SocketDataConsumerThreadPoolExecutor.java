package com.tk.socket;

import cn.hutool.core.thread.ThreadUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
public class SocketDataConsumerThreadPoolExecutor<C, D> {

    private final ThreadPoolExecutor dataThreadPoolExecutor;

    private final LinkedBlockingQueue<SocketDataConsumerDto<C, D>> dataQueue = new LinkedBlockingQueue<>();

    public ThreadPoolExecutor getDataThreadPoolExecutor() {
        return dataThreadPoolExecutor;
    }

    public LinkedBlockingQueue<SocketDataConsumerDto<C, D>> getDataQueue() {
        return dataQueue;
    }

    public SocketDataConsumerThreadPoolExecutor() {
        throw new SocketException("该类不可使用无参构造函数实例化");
    }

    public SocketDataConsumerThreadPoolExecutor(
            BiConsumer<C, D> dataConsumer
            , int maxDataThreadCount
            , int singleThreadDataConsumerCount
    ) {

        dataThreadPoolExecutor = ThreadUtil.newExecutor(2, Math.min(maxDataThreadCount, 2));

        Runnable dataRunnable = () -> {
            if (dataConsumer != null) {
                Thread thread = Thread.currentThread();
                while (!thread.isInterrupted()) {
                    try {
                        SocketDataConsumerDto<C, D> take = dataQueue.take();
                        dataConsumer.accept(take.getChannel(), take.getData());
                    } catch (Exception e) {
                        log.error("数据处理异常", e);
                    }
                }
            }
        };
        dataThreadPoolExecutor.execute(dataRunnable);

        Runnable dataCountRunnable = () -> {
            if (dataConsumer != null) {
                int i = 0;
                while (singleThreadDataConsumerCount > i) {
                    try {
                        //不阻塞
                        SocketDataConsumerDto<C, D> data = dataQueue.poll(1, TimeUnit.SECONDS);
                        if (data != null) {
                            dataConsumer.accept(data.getChannel(), data.getData());
                        }
                    } catch (Exception e) {
                        log.error("数据处理异常", e);
                    }
                    i++;
                }
            }
        };

        dataThreadPoolExecutor.execute(() -> {
            Thread thread = Thread.currentThread();
            while (!thread.isInterrupted()) {
                try {
                    int activeThreadCount = dataThreadPoolExecutor.getActiveCount();
                    int leftCanUseThreadCount = maxDataThreadCount - activeThreadCount;
                    int dataSize = dataQueue.size();
                    if (dataSize > singleThreadDataConsumerCount) {
                        int newThreadCount = Math.min(dataSize / singleThreadDataConsumerCount, leftCanUseThreadCount) - activeThreadCount;
                        if (newThreadCount > 0) {
                            for (int i = 0; i < newThreadCount; i++) {
                                this.dataThreadPoolExecutor.execute(dataCountRunnable);
                            }
                        }
                    } else if (dataSize > 0 && activeThreadCount < 1) {
                        this.dataThreadPoolExecutor.execute(dataCountRunnable);
                    }
                } catch (Exception e) {
                    log.error("data线程分配异常", e);
                }
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    log.info("data线程已关闭");
                    return;
                }
            }
        });
    }

    public void putData(C channel, D data) throws InterruptedException {
        //如果队列已满，需阻塞
        dataQueue.put(new SocketDataConsumerDto<>(channel, data));
    }

    public boolean shutdown() {
        try {
            dataThreadPoolExecutor.shutdown();
        } catch (Exception e) {
            log.error("socket消费线程池关闭失败，未处理数据条数：{}", dataQueue.size());
            throw new SocketException(e, "socket消费线程池关闭异常");
        }
        return true;
    }

}