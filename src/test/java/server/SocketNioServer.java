package server;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONUtil;
import com.tk.socket.*;
import com.tk.utils.Base64SecretUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
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

    private final Integer serverPort;

    private final Map<Integer, SocketJSONDataDto> syncDataMap = new ConcurrentHashMap<>();

    private final SocketDataHandler socketDataHandler = new SocketDataHandler();

    private final TypeReference<SocketJSONDataDto> socketDataDtoTypeReference = new TypeReference<SocketJSONDataDto>() {
    };

    public SocketNioServer(Integer serverPort) {
        this.serverPort = serverPort;
    }

    public void write(SocketJSONDataDto data, Channel channel) {
        write(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
    }

    public boolean writeAck(SocketJSONDataDto data, Channel channel) {
        return writeAck(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
    }

    public boolean writeAck(SocketJSONDataDto data, Channel channel, int seconds) {
        return writeAck(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8), seconds);
    }

    public SocketJSONDataDto writeSync(SocketJSONDataDto data, Channel channel) {
        return writeSync(data, 10, channel);
    }

    public SocketJSONDataDto writeSync(SocketJSONDataDto data, int seconds, Channel channel) {
        Integer dataId = data.getServerDataId();
        if (dataId == null) {
            dataId = ThreadLocalRandom.current().nextInt();
            data.setServerDataId(dataId);
        }
        SocketJSONDataDto syncDataDto = new SocketJSONDataDto(dataId, false);
        syncDataMap.put(dataId, syncDataDto);
        try {
            synchronized (syncDataDto) {
                write(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
                syncDataDto.wait(Math.min(TimeUnit.SECONDS.toMillis(seconds), 10000));
            }
            SocketJSONDataDto socketJSONDataDto = syncDataMap.remove(dataId);
            String method = socketJSONDataDto.getMethod();
            if (!StringUtils.equals(method, "syncReturn")) {
                throw new SocketException("写入超时");
            }
            return socketJSONDataDto;
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
    public SocketServerConfig setConfig() {
        SocketServerConfig socketServerConfig = new SocketServerConfig();
        socketServerConfig.setPort(serverPort);
        socketServerConfig.setMsgSizeLimit(null);
        socketServerConfig.setSingleThreadDataConsumerCount(100);
        socketServerConfig.setEventLoopThreadCount(10);
        socketServerConfig.setMaxHandlerDataThreadCount(200);
        socketServerConfig.setSingleThreadDataConsumerCount(100);
        return socketServerConfig;
    }

    @Override
    public BiConsumer<ChannelHandlerContext, byte[]> setDataConsumer() {
        return (ctx, bytes) -> {
            SocketJSONDataDto socketJSONDataDto = readSocketDataDto(bytes);
            Integer serverDataId = socketJSONDataDto.getServerDataId();
            if (serverDataId != null) {
                SocketJSONDataDto syncDataDto = syncDataMap.get(serverDataId);
                if (syncDataDto != null) {
                    synchronized (syncDataDto) {
                        syncDataDto.setData(socketJSONDataDto.getData());
                        syncDataDto.setMethod("syncReturn");
                        syncDataDto.notify();
                    }
                }
                //不再继续执行，即便method不为空
                return;
            }
            String method = socketJSONDataDto.getMethod();
            if (StringUtils.isNotBlank(method)) {
                Channel channel = ctx.channel();
                Object data = socketDataHandler.handle(socketJSONDataDto, SocketServerChannel.build(channel, this));
                Integer clientDataId = socketJSONDataDto.getClientDataId();
                if (clientDataId != null) {
                    SocketJSONDataDto syncDataDto;
                    syncDataDto = new SocketJSONDataDto(data);
                    syncDataDto.setClientDataId(clientDataId);
                    this.write(syncDataDto, channel);
                }
            }
        };
    }

    public SocketJSONDataDto readSocketDataDto(byte[] data) {
        return JSONUtil.toBean(new String(data, StandardCharsets.UTF_8), socketDataDtoTypeReference, true);
    }

    @Override
    public SocketEncodeDto encode(Channel channel, byte[] data) {
        String appKey = SocketChannelSecretUtil.getAppKey(channel);
        if (appKey != null) {
            byte[] serverSecret = SocketChannelSecretUtil.getSecret(appKey);
            if (serverSecret != null) {
                return new SocketEncodeDto(Base64SecretUtil.encodeToByteArray(data, serverSecret), (byte) 0xFF);
            }
        }
        return null;
    }

    @Override
    public byte[] decode(Channel channel, byte[] data, byte secretByte) {
        int appKeyLength = SocketMessageUtil.byteArrayToInt(data);
        String appKey = SocketChannelSecretUtil.getAppKey(channel);
        if (appKey == null) {
            byte[] appKeyBytes = new byte[appKeyLength];
            System.arraycopy(data, 4, appKeyBytes, 0, appKeyLength);
            appKey = new String(appKeyBytes, StandardCharsets.UTF_8);
            byte[] serverSecret = SocketChannelSecretUtil.getSecret(appKey);
            if (serverSecret != null) {
                int index = 4 + appKeyLength;
                int length = data.length - index;
                byte[] decode = new byte[length];
                System.arraycopy(data, index, decode, 0, length);
                byte[] bytes = Base64SecretUtil.decodeToByteArray(decode, serverSecret);
                SocketChannelSecretUtil.setAppKey(channel, appKey);
                return bytes;
            }
        } else {
            byte[] serverSecret = SocketChannelSecretUtil.getSecret(appKey);
            if (serverSecret != null) {
                int index = 4 + appKeyLength;
                int length = data.length - index;
                byte[] decode = new byte[length];
                System.arraycopy(data, index, decode, 0, length);
                return Base64SecretUtil.decodeToByteArray(decode, serverSecret);
            }
        }
        return null;
    }

    private static final AttributeKey<String> appKeyAttr = AttributeKey.valueOf("appKey");

    @Override
    public Boolean isClient(Channel channel) {
        return channel.hasAttr(appKeyAttr);
    }

    @Override
    protected Long setUnknownWaitMsgTimeoutSeconds() {
        return 2L;
    }
}
