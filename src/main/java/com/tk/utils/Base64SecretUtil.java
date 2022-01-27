package com.tk.utils;

import cn.hutool.crypto.SecureUtil;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public class Base64SecretUtil {

    private static final Base64.Encoder base64Encoder = Base64.getEncoder();

    private static final Base64.Decoder base64Decoder = Base64.getDecoder();

    /**
     * 根据密钥加密字符串
     *
     * @param data   数据
     * @param secret 密钥
     */
    public static String encodeToString(String data, String secret) {
        return base64Encoder.encodeToString(SecureUtil.aes(secret.getBytes(StandardCharsets.UTF_8)).encrypt((Objects.isNull(data) ? "" : data)));
    }

    /**
     * 根据密钥加密字符串
     *
     * @param data   数据
     * @param secret 密钥
     */
    public static String encodeToString(String data, byte[] secret) {
        return base64Encoder.encodeToString(SecureUtil.aes(secret).encrypt((Objects.isNull(data) ? "" : data)));
    }

    /**
     * 根据密钥加密字节数组
     *
     * @param data   数据
     * @param secret 密钥
     */
    public static String encodeToString(byte[] data, String secret) {
        return base64Encoder.encodeToString(SecureUtil.aes(secret.getBytes(StandardCharsets.UTF_8)).encrypt((Objects.isNull(data) ? new byte[0] : data)));
    }

    /**
     * 根据密钥加密字节数组
     *
     * @param data   数据
     * @param secret 密钥
     */
    public static String encodeToString(byte[] data, byte[] secret) {
        return base64Encoder.encodeToString(SecureUtil.aes(secret).encrypt((Objects.isNull(data) ? new byte[0] : data)));
    }

    /**
     * 根据密钥加密字符串
     *
     * @param data   数据
     * @param secret 密钥
     */
    public static byte[] encodeToByteArray(String data, String secret) {
        return base64Encoder.encode(SecureUtil.aes(secret.getBytes(StandardCharsets.UTF_8)).encrypt((Objects.isNull(data) ? "" : data)));
    }

    /**
     * 根据密钥加密字符串
     *
     * @param data   数据
     * @param secret 密钥
     */
    public static byte[] encodeToByteArray(String data, byte[] secret) {
        return base64Encoder.encode(SecureUtil.aes(secret).encrypt((Objects.isNull(data) ? "" : data)));
    }

    /**
     * 根据密钥加密字节数组
     *
     * @param data   数据
     * @param secret 密钥
     */
    public static byte[] encodeToByteArray(byte[] data, String secret) {
        return base64Encoder.encode(SecureUtil.aes(secret.getBytes(StandardCharsets.UTF_8)).encrypt((Objects.isNull(data) ? new byte[0] : data)));
    }

    /**
     * 根据密钥加密字节数组
     *
     * @param data   数据
     * @param secret 密钥
     */
    public static byte[] encodeToByteArray(byte[] data, byte[] secret) {
        return base64Encoder.encode(SecureUtil.aes(secret).encrypt((Objects.isNull(data) ? new byte[0] : data)));
    }

    /**
     * 根据密钥解密字符串
     *
     * @param data   已加密数据
     * @param secret 密钥
     */
    public static String decodeToString(String data, String secret) {
        return new String(SecureUtil.aes(secret.getBytes(StandardCharsets.UTF_8)).decrypt(base64Decoder.decode(data)));
    }

    /**
     * 根据密钥解密字符串
     *
     * @param data   已加密数据
     * @param secret 密钥
     */
    public static String decodeToString(String data, byte[] secret) {
        return new String(SecureUtil.aes(secret).decrypt(base64Decoder.decode(data)));
    }

    /**
     * 根据密钥解密字节数组
     *
     * @param data   已加密数据
     * @param secret 密钥
     */
    public static String decodeToString(byte[] data, String secret) {
        return new String(SecureUtil.aes(secret.getBytes(StandardCharsets.UTF_8)).decrypt(base64Decoder.decode(data)));
    }

    /**
     * 根据密钥解密字节数组
     *
     * @param data   已加密数据
     * @param secret 密钥
     */
    public static String decodeToString(byte[] data, byte[] secret) {
        return new String(SecureUtil.aes(secret).decrypt(base64Decoder.decode(data)));
    }

    /**
     * 根据密钥解密字符串
     *
     * @param data   已加密数据
     * @param secret 密钥
     */
    public static byte[] decodeToByteArray(String data, String secret) {
        return SecureUtil.aes(secret.getBytes(StandardCharsets.UTF_8)).decrypt(base64Decoder.decode(data));
    }

    /**
     * 根据密钥解密字符串
     *
     * @param data   已加密数据
     * @param secret 密钥
     */
    public static byte[] decodeToByteArray(String data, byte[] secret) {
        return SecureUtil.aes(secret).decrypt(base64Decoder.decode(data));
    }

    /**
     * 根据密钥解密字节数组
     *
     * @param data   已加密数据
     * @param secret 密钥
     */
    public static byte[] decodeToByteArray(byte[] data, String secret) {
        return SecureUtil.aes(secret.getBytes(StandardCharsets.UTF_8)).decrypt(base64Decoder.decode(data));
    }

    /**
     * 根据密钥解密字节数组
     *
     * @param data   已加密数据
     * @param secret 密钥
     */
    public static byte[] decodeToByteArray(byte[] data, byte[] secret) {
        return SecureUtil.aes(secret).decrypt(base64Decoder.decode(data));
    }

}
