package com.tk.socket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

@Slf4j
public class SocketMsgHandler {

    private final Map<String, SocketAckThreadDto> ackDataMap = new ConcurrentHashMap<>();

    private final BiConsumer<ChannelHandlerContext, byte[]> dataConsumer;

    private final ThreadPoolExecutor dataConsumerThreadPoolExecutor;

    public SocketMsgHandler() {
        throw new SocketException("该类不可使用无参构造函数实例化");
    }

    public SocketMsgHandler(
            BiConsumer<ChannelHandlerContext, byte[]> dataConsumer
            , int maxDataThreadCount
    ) {
        this.dataConsumer = dataConsumer;

        int corePoolSize = Runtime.getRuntime().availableProcessors();
        int maxPoolSize = Math.max(maxDataThreadCount, corePoolSize);
        this.dataConsumerThreadPoolExecutor = new ThreadPoolExecutor(corePoolSize, maxPoolSize, TimeUnit.SECONDS.toNanos(60), TimeUnit.NANOSECONDS, new LinkedBlockingQueue<>());

    }

    public void putData(ChannelHandlerContext channel, ByteBuf data) throws InterruptedException {
        //TODO 检查
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

    /**
     * 同步写
     *
     * @param socketChannel
     * @param data
     * @param seconds
     * @return
     */
    public boolean writeAck(Channel socketChannel, ByteBuf data, int seconds) {
        ByteBuf packageData = SocketMessageUtil.packageData(data, true);
        byte[] needAckBytes = {(byte) (packageData.getByte(0) & (byte) 0x7F), packageData.getByte(1), packageData.getByte(2)};
        int ackKey = SocketMessageUtil.threeByteArrayToInt(needAckBytes);
        String key = Integer.toString(ackKey).concat(socketChannel.id().asShortText());
        try {
            SocketAckThreadDto ackThreadDto = new SocketAckThreadDto();
            ackDataMap.put(key, ackThreadDto);
            synchronized (ackThreadDto) {
                socketChannel.writeAndFlush(packageData);
                log.debug("等待ack字节：{}", needAckBytes);
                ackThreadDto.wait(TimeUnit.SECONDS.toMillis(seconds));
            }
//            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(Math.min(seconds, 10)));
            SocketAckThreadDto socketAckThreadDto = ackDataMap.remove(key);
            return socketAckThreadDto != null && socketAckThreadDto.getIsAck();
        } catch (InterruptedException e) {
            log.error("同步写异常", e);
            ackDataMap.remove(key);
            throw new SocketException("同步写异常");
        } catch (Exception e) {
            log.error("同步写异常", e);
            ackDataMap.remove(key);
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