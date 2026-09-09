package com.emie.designpm.project.service;

/**
 * 校验"参考零售价"：0–1000，最多两位小数。
 * 原先在 ProjectService / DefaultSubTaskCommandService /
 * DefaultProjectLifecycleCommandService 各有一份逐字相同的私有方法。
 */
final class CustomPriceRangeValidator {

    private CustomPriceRangeValidator() {
    }

    static void validate(String value) {
        try {
            double price = Double.parseDouble(value.trim());
            if (!Double.isFinite(price) || price < 0 || price > 1000 || Math.round(price * 100) != price * 100) {
                throw new IllegalArgumentException("参考零售价必须在0到1,000之间，最多两位小数");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参考零售价必须在0到1,000之间，最多两位小数");
        }
    }
}
