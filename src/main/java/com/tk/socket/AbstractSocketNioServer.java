package com.tk.socket;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.unix.PreferredDirectByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
public abstract class AbstractSocketNioServer {

    private Boolean isInit = false;

    public Boolean getIsInit() {
        return isInit;
    }

    private Integer port;

    private final Cache<ChannelId, SocketMsgDto<ByteBuf>> readCacheMap = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.SECONDS)
            .removalListener((ChannelId key, SocketMsgDto<ByteBuf> value, RemovalCause removalCause) -> {
                ReferenceCountUtil.release(value.getMsg());
            })
            //key必须使用弱引用
            .weakKeys()
            .build();

    private DataConsumerThreadPoolExecutor<ChannelHandlerContext, byte[]> dataConsumerThreadPoolExecutor;

    private final int dataSizeLimit = Optional.ofNullable(setBytesSizeLimit()).orElse(4 * 1024 * 1024);

    private final Map<String, SocketAckThreadDto> ackDataMap = new ConcurrentHashMap<>();

    public abstract Integer setPort();

    public abstract SocketEncodeDto encode(ChannelId channelId, byte[] data);

    public abstract byte[] decode(ChannelId channelId, byte[] data, byte secretByte);

    public abstract BiConsumer<ChannelHandlerContext, byte[]> setDataConsumer();

    public abstract Integer setBytesSizeLimit();

    public abstract int setEventLoopThreadCount();

    public abstract int setMaxHandlerDataThreadCount();

    public abstract int setSingleThreadDataConsumerCount();

    @ChannelHandler.Sharable
    class ServerInHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            super.channelRegistered(ctx);
            log.info("客户端channelId：{}，已注册", ctx.channel().id());
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            super.channelUnregistered(ctx);
            log.info("客户端channelId：{}，已注销", ctx.channel().id());
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msgObj) throws Exception {
            ByteBuf msg = (ByteBuf) msgObj;
            Channel socketChannel = ctx.channel();
            ChannelId channelId = socketChannel.id();
            ByteBuf byteBuf = null;
            boolean isReleaseMsg = false;
            try {
                int readableBytes;
                Integer msgSize;
                int writeIndex;
                SocketMsgDto<ByteBuf> socketMsgDto = readCacheMap.getIfPresent(channelId);
                if (socketMsgDto == null) {
                    readableBytes = msg.readableBytes();
                    byteBuf = Unpooled.buffer();
                    if (readableBytes < 5) {
                        //数据不足，继续等待
                        byteBuf.writeBytes(msg);
                        //已读取完，可释放
                        isReleaseMsg = true;
                        readCacheMap.put(channelId, new SocketMsgDto<>(null, byteBuf));
                        return;
                    }
                    byteBuf.writeBytes(msg, 5);
                    msgSize = SocketMessageUtil.checkMsgFirst(byteBuf, dataSizeLimit);
                    //5+2+3+1
                    if (msgSize < 11) {
                        //丢弃并关闭连接
                        throw new BusinessException("报文格式错误");
                    }
                    writeIndex = byteBuf.writerIndex();
                    readCacheMap.put(channelId, new SocketMsgDto<>(msgSize, byteBuf));
                } else {
                    byteBuf = socketMsgDto.getMsg();
                    msgSize = socketMsgDto.getSize();
                    if (msgSize == null) {
                        //补全数据
                        int writerIndex = byteBuf.writerIndex();
                        if (writerIndex < 4) {
                            readableBytes = msg.readableBytes();
                            if (writerIndex + readableBytes < 4) {
                                //数据不足，继续等待
                                byteBuf.writeBytes(msg);
                                //已读取完，可释放
                                isReleaseMsg = true;
                                socketMsgDto.setMsg(byteBuf);
                                //刷新
                                //readCacheMap.put(channelId, socketMsgDto);
                                return;
                            }
                        }
                        msgSize = SocketMessageUtil.checkMsgFirst(byteBuf, dataSizeLimit);
                        if (msgSize < 11) {
                            //丢弃并关闭连接
                            throw new BusinessException("报文格式错误");
                        }
                        socketMsgDto.setSize(msgSize);
                        //刷新
                        //readCacheMap.put(channelId, socketMsgDto);
                    }
                    writeIndex = byteBuf.writerIndex();
                }
                boolean isStick = false;
                int leftDataIndex = msgSize - writeIndex;
                if (leftDataIndex > 0) {
                    readableBytes = msg.readableBytes();
                    int i = leftDataIndex - readableBytes;
                    if (i > 0) {
                        //继续读取
                        byteBuf.writeBytes(msg);
                        //已读取完，可释放
                        isReleaseMsg = true;
                        /*if (log.isDebugEnabled()) {
                            log.debug("byteBuf处理中 已读：{}，已写：{}，容量，{}", byteBuf.readerIndex(), byteBuf.writerIndex(), byteBuf.capacity());
                            log.debug("msg处理中 已读：{}，已写：{}，容量，{}", msg.readerIndex(), msg.writerIndex(), msg.capacity());
                        }*/
                        return;
                    } else if (i < 0) {
                        //此处粘包了，手动切割
                        byteBuf.writeBytes(msg, leftDataIndex);
                        isStick = true;
                    } else {
                        byteBuf.writeBytes(msg);
                        //已读取完，可释放
                        isReleaseMsg = true;
                    }
                } else if (leftDataIndex < 0) {
                    //丢弃并关闭连接
                    throw new BusinessException("报文数据异常");
                }
                Byte secretByte = SocketMessageUtil.checkMsgTail(byteBuf, msgSize);
                if (secretByte == null) {
                    //丢弃并关闭连接
                    throw new BusinessException("报文尾部验证失败");
                }
                //读取完成，写入队列
                byte[] decodeBytes;
                try {
                    byte[] bytes = new byte[msgSize - 8];
                    byteBuf.getBytes(5, bytes);
                    /*if (log.isDebugEnabled()) {
                        log.debug("byteBuf完成 已读：{}，已写：{}，容量，{}", byteBuf.readerIndex(), byteBuf.writerIndex(), byteBuf.capacity());
                        log.debug("msg完成 已读：{}，已写：{}，容量，{}", msg.readerIndex(), msg.writerIndex(), msg.capacity());
                    }*/
                    decodeBytes = decode(channelId, bytes, secretByte);
                } catch (Exception e) {
                    log.debug("服务端解码错误", e);
                    //丢弃并关闭连接
                    throw new BusinessException("报文解码错误");
                }
                int length = decodeBytes.length;
                boolean isAckData = SocketMessageUtil.isAckData(decodeBytes);
                log.debug("服务端接收isAckData：{}", isAckData);
                if (length > 3) {
                    log.debug("服务端开始处理数据，channelId：{}", channelId);
                    dataConsumerThreadPoolExecutor.putData(ctx, SocketMessageUtil.unPackageData(decodeBytes));
                } else {
                    if (!isAckData) {
                        //接收ackBytes
                        byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                        log.debug("服务端ack接收，ackBytes：{}", ackBytes);
                        int ackKey = SocketMessageUtil.threeByteArrayToInt(ackBytes);
                        String key = Integer.toString(ackKey).concat(channelId.asShortText());
                        SocketAckThreadDto socketAckThreadDto = ackDataMap.get(key);
                        if (socketAckThreadDto != null) {
                            socketAckThreadDto.setIsAck(true);
                            synchronized (socketAckThreadDto) {
                                socketAckThreadDto.notify();
                            }
//                            LockSupport.unpark(socketAckThreadDto.getThread());
                            log.debug("服务端ack成功，ackBytes：{}", ackBytes);
                            readCacheMap.invalidate(channelId);
                            return;
                        }
                    } else {
                        //关闭连接
                        throw new BusinessException("报文数据异常");
                    }
                    //丢弃
                    readCacheMap.invalidate(channelId);
                    return;
                }
                if (isAckData) {
                    byte[] ackBytes = SocketMessageUtil.getAckData(decodeBytes);
                    try {
                        socketChannel.writeAndFlush(ackBytes);
                        log.debug("服务端已发送ackBytes：{}", ackBytes);
                        readCacheMap.invalidate(channelId);
                        return;
                    } catch (Exception e) {
                        log.error("服务端ack编码错误", e);
                    }
                }
                //正常丢弃
                readCacheMap.invalidate(channelId);
                if (isStick) {
                    int newReadableBytes = msg.readableBytes();
                    ByteBuf newByteBuf = Unpooled.buffer();
                    if (newReadableBytes < 5) {
                        //数据不足，继续等待
                        newByteBuf.writeBytes(msg);
                        //已读取完，可释放
                        isReleaseMsg = true;
                        readCacheMap.put(channelId, new SocketMsgDto<>(null, newByteBuf));
                        return;
                    }
                    newByteBuf.writeBytes(msg, newReadableBytes);
                    int newMsgSize = SocketMessageUtil.checkMsgFirst(newByteBuf, dataSizeLimit);
                    //5+2+3+1
                    if (newMsgSize < 11) {
                        //丢弃并关闭连接
                        throw new BusinessException("报文格式错误");
                    }
                    readCacheMap.put(channelId, new SocketMsgDto<>(newMsgSize, newByteBuf));
                }
            } catch (Exception e) {
                log.error("服务端数据解析异常", e);
                //丢弃数据并关闭连接
                isReleaseMsg = true;
                socketChannel.close();
                //异常才释放
                readCacheMap.invalidate(channelId);
            } finally {
                if (isReleaseMsg) {
                    ReferenceCountUtil.release(msg);
                }
            }
        }
    }

    @ChannelHandler.Sharable
    class ServerOutHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof byte[]) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(encode(ctx.channel().id(), (byte[]) msg))), promise);
            } else {
                //传输其他类型数据时暂不支持ACK，需使用byte[]
                ctx.writeAndFlush(Unpooled.wrappedBuffer(SocketMessageUtil.packageMsg(encode(ctx.channel().id(), SocketMessageUtil.packageData(JSONUtil.toJsonStr(msg).getBytes(StandardCharsets.UTF_8), false)))), promise);
            }
            log.debug("服务端数据已发送，channelId：{}", ctx.channel().id());
        }
    }

    public void write(Channel socketChannel, byte[] data) {
        try {
            socketChannel.writeAndFlush(SocketMessageUtil.packageData(data, false));
        } catch (Exception e) {
            log.error("SocketNioServer写入异常", e);
            throw e;
        }
    }

    /**
     * 服务端等待确认时间最久10秒
     *
     * @param socketChannel
     * @param data
     * @param seconds
     * @return
     */
    public boolean writeAck(Channel socketChannel, byte[] data, int seconds) {
        byte[] packageData = SocketMessageUtil.packageData(data, true);
        byte[] needAckBytes = {(byte) (packageData[0] & (byte) 0x7F), packageData[1], packageData[2]};
        int ackKey = SocketMessageUtil.threeByteArrayToInt(needAckBytes);
        String key = Integer.toString(ackKey).concat(socketChannel.id().asShortText());
        socketChannel.writeAndFlush(packageData);
        try {
            SocketAckThreadDto ackThreadDto = new SocketAckThreadDto();
            ackDataMap.put(key, ackThreadDto);
            synchronized (ackThreadDto) {
                ackThreadDto.wait(TimeUnit.SECONDS.toNanos(Math.min(seconds, 10)));
            }
//            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(Math.min(seconds, 10)));
            SocketAckThreadDto socketAckThreadDto = ackDataMap.remove(key);
            return socketAckThreadDto != null && socketAckThreadDto.getIsAck();
        } catch (InterruptedException e) {
            log.error("SocketNioServer写入异常", e);
            ackDataMap.remove(key);
            throw new BusinessException("SocketNioServer写入异常");
        } catch (Exception e) {
            log.error("setAndWaitAck异常", e);
            ackDataMap.remove(key);
            throw e;
        }
    }

    public boolean writeAck(Channel socketChannel, byte[] data) {
        return writeAck(socketChannel, data, 10);
    }

    public synchronized void initNioServer() {
        ThreadUtil.execute(() -> {
            //new 一个主线程组
            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            //new 一个工作线程组
            EventLoopGroup workGroup = new NioEventLoopGroup(setEventLoopThreadCount());
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline()
                                    .addLast(new ServerInHandler())
                                    .addLast(new ServerOutHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    //首选直接内存
                    .option(ChannelOption.ALLOCATOR, PreferredDirectByteBufAllocator.DEFAULT)
                    //设置队列大小
//                .option(ChannelOption.SO_BACKLOG, 1024)
                    // 两小时内没有数据的通信时,TCP会自动发送一个活动探测数据报文
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.SO_RCVBUF, 128 * 1024)
                    .childOption(ChannelOption.SO_SNDBUF, 1024 * 1024)
                    //服务端低水位线设置为3M，高水位线设置为6M
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(3 * 1024 * 1024, 6 * 1024 * 1024))
                    ;
            try {
                port = setPort();
                if (dataConsumerThreadPoolExecutor == null) {
                    dataConsumerThreadPoolExecutor = new DataConsumerThreadPoolExecutor<>(
                            setDataConsumer()
                            , setMaxHandlerDataThreadCount()
                            , setSingleThreadDataConsumerCount()
                    );
                }
                ChannelFuture future = bootstrap.bind(port).sync();
                log.info("SocketNioServer已启动，开始监听端口: {}", port);
                isInit = true;
                future.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                //关闭主线程组
                bossGroup.shutdownGracefully();
                //关闭工作线程组
                workGroup.shutdownGracefully();
            }
        });
    }

}
