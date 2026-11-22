package com.mcargus.discovery;

import com.mcargus.i18n.Messages;

/** 加载器类型（从参数文件所在路径推断）。 */
public enum LoaderType {
    NEOFORGE, FORGE, UNKNOWN;

    public String display() {
        if (this == NEOFORGE) {
            return "NeoForge";
        }
        if (this == FORGE) {
            return "Forge";
        }
        return Messages.get("loader.unknown");
    }
}
