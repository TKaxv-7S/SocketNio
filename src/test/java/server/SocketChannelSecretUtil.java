package server;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SocketChannelSecretUtil {

    private static final AttributeKey<String> appKeyAttr = AttributeKey.valueOf("appKey");

    private static final Map<String, byte[]> secretCacheMap = new ConcurrentHashMap<>();

    public static void setAppKey(Channel channel, String appKey) {
        channel.attr(appKeyAttr).set(appKey);
    }

    public static String getAppKey(Channel channel) {
        return channel.attr(appKeyAttr).get();
    }

    public static Boolean hasAppKey(Channel channel) {
        return channel.hasAttr(appKeyAttr);
    }

    public static void delAppKey(Channel channel) {
        channel.attr(appKeyAttr).set(null);
    }

    public static byte[] getSecret(String appKey) {
        return secretCacheMap.get(appKey);
    }

    public static void setSecret(String appKey, byte[] secretBytes) {
        secretCacheMap.put(appKey, secretBytes);
    }

}
