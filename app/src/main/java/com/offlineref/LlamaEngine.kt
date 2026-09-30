package com.offlineref

// ШАГ 3a: обёртка над нативной библиотекой.
// System.loadLibrary бросит UnsatisfiedLinkError, если .so не загрузился
// (16KB-страницы, неверный ABI, битый файл) - MainActivity покажет это
// на аварийном экране с логом (правило 4 playbook).

object LlamaEngine {
    init {
        System.loadLibrary("llamajni")
    }

    external fun nativeHello(): String

    // ШАГ 3b: loadModel(path), generate(prompt, callback), unload()
}
