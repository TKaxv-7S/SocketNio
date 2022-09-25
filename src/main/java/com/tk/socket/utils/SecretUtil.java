package com.tk.socket.utils;

import com.tk.socket.SocketException;
import com.tk.socket.entity.SocketDecrypt;
import com.tk.socket.entity.SocketEncrypt;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Slf4j
public class SecretUtil {

    public static Cipher getAESEncryptCipher(byte[] secretBytes) {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secretBytes, "AES"));
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
        return cipher;
    }

    public static Cipher getAESDecryptCipher(byte[] secretBytes) {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secretBytes, "AES"));
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
        return cipher;
    }

    public static SocketEncrypt getAESEncrypt(byte[] secretBytes) {
        Cipher cipher = getAESEncryptCipher(secretBytes);
        return getEncrypt(cipher);
    }

    public static SocketDecrypt getAESDecrypt(byte[] secretBytes) {
        Cipher cipher = getAESDecryptCipher(secretBytes);
        return getDecrypt(cipher);
    }

    public static SocketEncrypt getEncrypt(Cipher cipher) {
        return data -> {
            try {
                if (data == null || !data.hasRemaining()) {
                    return data;
                }
                ByteBuffer encrypted;
                synchronized (cipher) {
                    encrypted = ByteBuffer.allocate(cipher.getOutputSize(data.remaining()));
                    cipher.doFinal(data, encrypted);
                }
                encrypted.flip();
                return encrypted;
            } catch (Exception e) {
                throw new SocketException(e, e.getMessage());
            }
        };
    }

    public static SocketDecrypt getDecrypt(Cipher cipher) {
        return data -> {
            try {
                if (data == null || !data.hasRemaining()) {
                    return data;
                }
                ByteBuffer decrypted;
                synchronized (cipher) {
                    decrypted = ByteBuffer.allocate(cipher.getOutputSize(data.remaining()));
                    cipher.doFinal(data, decrypted);
                }
                decrypted.flip();
                return decrypted;
            } catch (Exception e) {
                throw new SocketException(e, e.getMessage());
            }
        };
    }

}
