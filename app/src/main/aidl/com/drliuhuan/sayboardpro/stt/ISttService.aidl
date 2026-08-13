// 识别模型进程（:stt）服务接口。
// IME 进程经 bindService 获取本接口，把 16kHz 16bit 单声道 PCM 字节流喂给
// 常驻的 sherpa recognizer；结果经 ISttCallback 回传 IME。
package com.drliuhuan.sayboardpro.stt;

import com.drliuhuan.sayboardpro.stt.ISttCallback;

interface ISttService {
    // 预热：加载/复用 recognizer。幂等——已就绪直接返回 true；加载中或刚发起加载返回 false
    //（IME 侧轮询本方法直到 true，加载失败经 ISttCallback.onError(-1, ...) 通知）。
    boolean prepare();

    // 开始识别会话：注入热词（createStream 用）与会话 streamId。recognizer 就绪返回 true。
    boolean start(int streamId, String hotwords, double score);

    // 喂入 PCM 音频（short[] 的字节视图，小端）。模型进程转 short[] 后 acceptWaveform。
    void feedAudio(int streamId, in byte[] samples);

    // 结束识别（触发 onFinal）。
    void stop(int streamId);

    // 放弃识别（不提交）。
    void cancel(int streamId);

    // 注册结果回调（每次 bind 后由 IME 调用；旧回调会被替换）。
    void setListener(in ISttCallback cb);

    // 释放模型进程资源（可选：IME onDestroy 时通知；常驻场景 IME 不调用以保留 recognizer）。
    void destroy();
}
