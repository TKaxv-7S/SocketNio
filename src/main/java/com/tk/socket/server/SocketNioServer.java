package com.tk.socket.server;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONUtil;
import com.tk.socket.SocketEncodeDto;
import com.tk.socket.SocketException;
import com.tk.socket.SocketMessageUtil;
import com.tk.socket.SocketMsgDataDto;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
public class SocketNioServer extends AbstractSocketNioServer {

    private final SocketServerHandler socketServerHandler;

    private final Map<Integer, SocketMsgDataDto> syncDataMap = new ConcurrentHashMap<>();

    private final SocketClientCache<? extends SocketSecretDto> socketClientCache;

    private final TypeReference<SocketMsgDataDto> socketDataDtoTypeReference = new TypeReference<SocketMsgDataDto>() {
    };

    public SocketClientCache<? extends SocketSecretDto> getSocketClientCache() {
        return socketClientCache;
    }

    public SocketNioServer(SocketServerConfig config, SocketServerHandler socketServerHandler) {
        super(config);
        this.socketServerHandler = socketServerHandler;
        this.socketClientCache = new SocketClientCache<>();
    }

    public SocketNioServer(SocketServerConfig config, SocketServerHandler socketServerHandler, SocketClientCache<? extends SocketSecretDto> socketClientCache) {
        super(config);
        this.socketServerHandler = socketServerHandler;
        this.socketClientCache = socketClientCache;
    }

    public void write(SocketMsgDataDto data, Channel channel) {
        write(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
    }

    public boolean writeAck(SocketMsgDataDto data, Channel channel) {
        return writeAck(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
    }

    public boolean writeAck(SocketMsgDataDto data, Channel channel, int seconds) {
        return writeAck(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8), seconds);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data, Channel channel) {
        return writeSync(data, 10, channel);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data, int seconds, Channel channel) {
        Integer dataId = data.getServerDataId();
        if (dataId == null) {
            dataId = ThreadLocalRandom.current().nextInt();
            data.setServerDataId(dataId);
        }
        SocketMsgDataDto syncDataDto = new SocketMsgDataDto(dataId, false);
        syncDataMap.put(dataId, syncDataDto);
        try {
            synchronized (syncDataDto) {
                write(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
                syncDataDto.wait(TimeUnit.SECONDS.toMillis(seconds));
            }
            SocketMsgDataDto socketDataDto = syncDataMap.remove(dataId);
            String method = socketDataDto.getMethod();
            if (!StringUtils.equals(method, "syncReturn")) {
                throw new SocketException("写入超时");
            }
            return socketDataDto;
        } catch (InterruptedException e) {
            log.error("同步写入异常", e);
            syncDataMap.remove(dataId);
            throw new SocketException("同步写入异常");
        } catch (Exception e) {
            log.error("同步写入异常", e);
            syncDataMap.remove(dataId);
            throw e;
        }
    }

    @Override
    public BiConsumer<ChannelHandlerContext, byte[]> setDataConsumer() {
        return (ctx, bytes) -> {
            SocketMsgDataDto socketDataDto = readSocketDataDto(bytes);
            Integer serverDataId = socketDataDto.getServerDataId();
            if (serverDataId != null) {
                SocketMsgDataDto syncDataDto = syncDataMap.get(serverDataId);
                if (syncDataDto != null) {
                    synchronized (syncDataDto) {
                        syncDataDto.setData(socketDataDto.getData());
                        syncDataDto.setMethod("syncReturn");
                        syncDataDto.notify();
                    }
                }
                //不再继续执行，即便method不为空
                return;
            }
            String method = socketDataDto.getMethod();
            if (StringUtils.isNotBlank(method)) {
                if (socketDataDto.isSuccess()) {
                    Channel channel = ctx.channel();
                    Integer clientDataId = socketDataDto.getClientDataId();
                    SocketMsgDataDto syncDataDto;
                    try {
                        log.debug("客户端执行method：{}", method);
                        syncDataDto = socketServerHandler.handle(method, socketDataDto, socketClientCache.getClientChannel(channel));
                    } catch (Exception e) {
                        syncDataDto = SocketMsgDataDto.buildError(e.getMessage());
                    }
                    if (clientDataId != null) {
                        syncDataDto.setClientDataId(clientDataId);
                        write(syncDataDto, channel);
                    }
                } else {
                    log.error("客户端处理失败：{}", JSONUtil.toJsonStr(socketDataDto));
                }
            } else {
                //客户端心跳
                socketClientCache.getClientChannel(ctx.channel());
            }
        };
    }

    @Override
    public Boolean isClient(Channel channel) {
        return socketClientCache.hasAppKey(channel);
    }

    public SocketMsgDataDto readSocketDataDto(byte[] data) {
        return JSONUtil.toBean(new String(data, StandardCharsets.UTF_8), socketDataDtoTypeReference, true);
    }

    @Override
    public SocketEncodeDto encode(Channel channel, byte[] data) {
        SocketSecretDto secret = socketClientCache.getSecret(channel);
        if (secret != null) {
            return new SocketEncodeDto(secret.encode(data), (byte) 0xFF);
        }
        return null;
    }

    @Override
    public byte[] decode(Channel channel, byte[] data, byte secretByte) {
        int appKeyLength = SocketMessageUtil.byteArrayToInt(data);
        String appKey = socketClientCache.getAppKey(channel);
        if (appKey == null) {
            byte[] appKeyBytes = new byte[appKeyLength];
            System.arraycopy(data, 4, appKeyBytes, 0, appKeyLength);
            appKey = new String(appKeyBytes, StandardCharsets.UTF_8);
            SocketSecretDto secret = socketClientCache.getSecret(appKey);
            if (secret != null) {
                int index = 4 + appKeyLength;
                int length = data.length - index;
                byte[] decode = new byte[length];
                System.arraycopy(data, index, decode, 0, length);
                byte[] bytes = secret.decode(decode);
                if (!socketClientCache.addClientChannel(appKey, channel, this)) {
                    log.error("appKey：{}，客户端连接数超出限制", appKey);
                    throw new SocketException("客户端连接数超出限制");
                }
                return bytes;
            }
        } else {
            SocketSecretDto secret = socketClientCache.getSecret(appKey);
            if (secret != null) {
                int index = 4 + appKeyLength;
                int length = data.length - index;
                byte[] decode = new byte[length];
                System.arraycopy(data, index, decode, 0, length);
                return secret.decode(decode);
            }
        }
        throw new SocketException("客户端未配置");
    }

    @Override
    protected void channelUnregisteredEvent(ChannelHandlerContext ctx) {
        socketClientCache.delClientChannel(ctx.channel());
        super.channelUnregisteredEvent(ctx);
    }
}
