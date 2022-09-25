package com.tk.socket.entity;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class LoopArray<T> {

    private final AtomicInteger indexAtomic;

    private final int maxIndex;

    private final Object[] array;

    public LoopArray(Supplier<T> supplier, int size) {
        array = new Object[size];
        indexAtomic = new AtomicInteger(0);
        maxIndex = array.length - 2;
        for (int i = 0; i < size; i++) {
            array[i] = supplier.get();
        }
    }

    public T getByLoop() {
        int index = indexAtomic.getAndUpdate(operand -> {
            if (operand > maxIndex) {
                operand = 0;
            } else {
                operand++;
            }
            return operand;
        });
        return get(index);
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        return (T) array[index];
    }
}
