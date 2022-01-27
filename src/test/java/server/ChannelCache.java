package server;

import io.netty.channel.ChannelId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChannelCache {

    private static final Map<ChannelId, String> appKeyCache = new ConcurrentHashMap<>();

    private static final Map<String, byte[]> secretCacheMap = new ConcurrentHashMap<>();

    public static void setAppKey(ChannelId channelId, String appKey) {
        appKeyCache.put(channelId, appKey);
    }

    public static String getAppKey(ChannelId channelId) {
        return appKeyCache.get(channelId);
    }

    public static byte[] getSecret(String appKey) {
        return secretCacheMap.get(appKey);
    }

    public static void setSecret(String appKey, byte[] secretBytes) {
        secretCacheMap.put(appKey, secretBytes);
    }

}
