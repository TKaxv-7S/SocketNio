package com.tk.socket.entity;

import com.tk.socket.SocketException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

public class SocketSecret {

    private final Cipher encryptCipher;

    private final Cipher decryptCipher;

    public SocketSecret(Cipher encryptCipher, Cipher decryptCipher) {
        this.encryptCipher = encryptCipher;
        this.decryptCipher = decryptCipher;
    }

    public byte[] encode(byte[] data) {
        try {
            return encryptCipher.doFinal(data);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new SocketException(e, e.getMessage());
        }
    }

    public byte[] decode(byte[] data) {
        try {
            return decryptCipher.doFinal(data);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new SocketException(e, e.getMessage());
        }
    }

}
