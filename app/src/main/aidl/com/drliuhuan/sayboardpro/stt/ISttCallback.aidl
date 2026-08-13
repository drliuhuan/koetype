// 识别结果回调：模型进程（:stt）→ IME 进程。
// IME 每次 bind 后经 ISttService.setListener 注册；streamId 与 ISttService.start 传入一致。
// streamId < 0 表示全局/预热错误（模型加载失败等，与具体会话无关）。
package com.drliuhuan.sayboardpro.stt;

interface ISttCallback {
    // 实时部分结果（上屏为 composing 文本）
    void onPartial(int streamId, String text);

    // 最终结果（上屏为已提交文本）
    void onFinal(int streamId, String text);

    // 错误（streamId<0 时为模型进程全局错误）
    void onError(int streamId, String message);
}
