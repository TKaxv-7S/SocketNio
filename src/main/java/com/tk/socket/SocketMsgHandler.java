package com.tk.socket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

@Slf4j
public class SocketMsgHandler {

    private final Map<String, SocketAckThreadDto> ackDataMap = new ConcurrentHashMap<>();

    private final BiConsumer<Channel, byte[]> dataConsumer;

    private final ThreadPoolExecutor dataConsumerThreadPoolExecutor;

    public SocketMsgHandler() {
        throw new SocketException("该类不可使用无参构造函数实例化");
    }

    public SocketMsgHandler(
            BiConsumer<Channel, byte[]> dataConsumer
            , int maxDataThreadCount
    ) {
        this.dataConsumer = dataConsumer;

        int corePoolSize = Runtime.getRuntime().availableProcessors();
        int maxPoolSize = Math.max(maxDataThreadCount, corePoolSize);
        this.dataConsumerThreadPoolExecutor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                TimeUnit.SECONDS.toNanos(60), TimeUnit.NANOSECONDS,
                new SynchronousQueue<>(),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void read(Channel channel, ByteBuf data) {
        byte[] bytes = new byte[data.writerIndex()];
        data.getBytes(0, bytes);
        //如果队列已满，需阻塞
        dataConsumerThreadPoolExecutor.execute(() -> dataConsumer.accept(channel, bytes));
    }

    public void write(Channel socketChannel, ByteBuf data) {
        try {
            socketChannel.writeAndFlush(SocketMessageUtil.packageData(data, false));
        } catch (Exception e) {
            log.error("写入异常", e);
            throw e;
        }
    }

    public boolean shutdownNow() {
        try {
            shutdownAndAwaitTermination(dataConsumerThreadPoolExecutor, 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("socket消费线程池关闭失败");
            throw new SocketException(e, "socket消费线程池关闭异常");
        }
        ackDataMap.clear();
        return true;
    }

    public static void shutdownAndAwaitTermination(ExecutorService pool, long timeout, TimeUnit unit) {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    if (!pool.awaitTermination(timeout, unit)) {
                        log.info("线程池暂时无法关闭");
                    }
                }
            } catch (InterruptedException ie) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}