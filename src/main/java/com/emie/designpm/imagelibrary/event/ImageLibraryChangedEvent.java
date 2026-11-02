package com.emie.designpm.imagelibrary.event;

import java.util.List;

/** 图档保存成功后更新视觉索引；事务回滚时不提交分析任务。 */
public record ImageLibraryChangedEvent(List<String> storedNames) {
    public ImageLibraryChangedEvent {
        storedNames = storedNames == null ? List.of() : List.copyOf(storedNames);
    }
}
