package com.tk.socket;

import cn.hutool.core.thread.ThreadUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
public class DataConsumerThreadPoolExecutor<C, D> {

    private final ThreadPoolExecutor dataThreadPoolExecutor;

    private final LinkedBlockingQueue<SocketDataConsumerDto<C, D>> dataQueue = new LinkedBlockingQueue<>();

    public ThreadPoolExecutor getDataThreadPoolExecutor() {
        return dataThreadPoolExecutor;
    }

    public LinkedBlockingQueue<SocketDataConsumerDto<C, D>> getDataQueue() {
        return dataQueue;
    }

    public void putData(C channel, D data) throws InterruptedException {
        //如果队列已满，需阻塞
        dataQueue.put(new SocketDataConsumerDto<>(channel, data));
    }

    /*public boolean setAndWaitAck(Object key, int seconds) {
        try {
            Thread thread = Thread.currentThread();
            SocketAckThreadDto ackThreadDto = new SocketAckThreadDto(thread);
            ackDataMap.put(key, ackThreadDto);
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(seconds));
//            synchronized (ackThreadDto) {
//                ackThreadDto.wait(TimeUnit.SECONDS.toNanos(seconds));
//            }
            SocketAckThreadDto socketAckThreadDto = ackDataMap.remove(key);
            return socketAckThreadDto != null && socketAckThreadDto.getIsAck();
        } catch (Exception e) {
            log.error("setAndWaitAck异常", e);
            ackDataMap.remove(key);
            throw e;
        }
    }

    public boolean getAck(Object key) {
        SocketAckThreadDto socketAckThreadDto = ackDataMap.get(key);
        if (socketAckThreadDto != null) {
            socketAckThreadDto.setIsAck(true);
            LockSupport.unpark(socketAckThreadDto.getThread());
//            synchronized (socketAckThreadDto) {
//                socketAckThreadDto.notify();
//            }
            return true;
        }
        return false;
    }*/

    public DataConsumerThreadPoolExecutor() {
        throw new BusinessException("该类不可使用无参构造函数实例化");
    }

    public DataConsumerThreadPoolExecutor(
            BiConsumer<C, D> dataConsumer
            , int maxDataThreadCount
            , int singleThreadDataConsumerCount
    ) {

        dataThreadPoolExecutor = ThreadUtil.newExecutor(1, maxDataThreadCount);

        Runnable dataRunnable = () -> {
            if (dataConsumer != null) {
                for (; ; ) {
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

        new Thread(() -> {
            for (; ; ) {
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
                    log.error("data线程分配睡眠异常", e);
                }
            }
        }).start();
    }

}