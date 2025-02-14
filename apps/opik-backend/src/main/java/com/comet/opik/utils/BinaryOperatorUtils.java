package com.comet.opik.utils;

import lombok.experimental.UtilityClass;

import java.util.function.BinaryOperator;

@UtilityClass
public class BinaryOperatorUtils {

    public <T> BinaryOperator<T> last() {
        return (oldValue, newValue) -> newValue;
    }

}
