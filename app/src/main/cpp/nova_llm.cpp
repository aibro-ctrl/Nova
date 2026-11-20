// Офлайн-модель Нова: тонкая JNI-обёртка над llama.cpp.
// Одна модель в памяти, генерация по одному запросу за раз (синхронизацию делает Kotlin).
#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <exception>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "NovaLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> stop{false};
};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Модель иногда режет UTF-8 посреди символа: отдаём в Java только целые символы.
size_t complete_utf8_prefix(const std::string &s) {
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return (back + 1 >= need) ? s.size() : i - 1;
    }
    return s.size();
}

// Стандартный UTF-8 (с эмодзи) → String через byte[]: NewStringUTF принимает только «модифицированный» UTF-8.
jstring to_jstring(JNIEnv *env, const std::string &s) {
    // Уходить в Java с непогашенным исключением нельзя: ART в этом случае прерывает процесс.
    if (env->ExceptionCheck()) env->ExceptionClear();
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    auto *res = (jstring) env->NewObject(strCls, ctor, bytes, enc);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(strCls);
    return res;
}

// Сэмплер освобождается даже если llama.cpp бросит исключение посреди генерации.
struct SamplerGuard {
    llama_sampler *smpl = nullptr;
    ~SamplerGuard() { if (smpl) llama_sampler_free(smpl); }
};

std::string chatml(const std::vector<std::string> &roles, const std::vector<std::string> &texts) {
    std::string p;
    for (size_t i = 0; i < roles.size(); i++) p += "<|im_start|>" + roles[i] + "\n" + texts[i] + "<|im_end|>\n";
    p += "<|im_start|>assistant\n";
    return p;
}

/**
 * Qwen3 и Qwen3.5 — «думающие» модели: их шаблон при выключенном enable_thinking сам вставляет
 * пустой блок «<think>\n\n</think>\n\n» после «assistant». llama.cpp 0.5.0 не умеет jinja-шаблоны,
 * распознаёт такие модели как ChatML и этот префикс теряет — тогда модель уходит в долгие рассуждения,
 * и ответа не дождаться. Возвращаем префикс сами.
 */
void disable_thinking_if_needed(llama_model *model, std::string &prompt) {
    if (prompt.empty()) return;
    char arch[64] = {0};
    if (llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch)) < 0) return;
    if (std::string(arch).rfind("qwen3", 0) != 0 && std::string(arch).rfind("qwen35", 0) != 0) return;
    static const std::string tail = "<|im_start|>assistant\n";
    if (prompt.size() < tail.size() || prompt.compare(prompt.size() - tail.size(), tail.size(), tail) != 0) return;
    prompt += "<think>\n\n</think>\n\n";
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_ai_nova_app_llm_LlamaNative_init(JNIEnv *, jclass) {
    try {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
    } catch (const std::exception &e) {
        // llama.cpp бросает std::runtime_error (в том числе при нехватке памяти) — через JNI это SIGABRT.
        LOGW("backend init failed: %s", e.what());
    } catch (...) {
        LOGW("backend init failed");
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_nova_app_llm_LlamaNative_load(JNIEnv *env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    std::string path = jstr(env, jpath);
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    // llama.cpp объявляет ошибки исключениями (включая «failed to allocate compute buffers» при нехватке ОЗУ).
    // Неперехваченное C++ исключение на границе JNI = SIGABRT и закрытое приложение — ловим и возвращаем 0.
    try {
        llama_model_params mp = llama_model_default_params();
        model = llama_model_load_from_file(path.c_str(), mp);
        if (!model) { LOGW("model load failed"); return 0; }

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(n_ctx);
        cp.n_batch = 512;
        cp.n_ubatch = 512;
        cp.n_threads = n_threads;
        cp.n_threads_batch = n_threads;
        ctx = llama_init_from_model(model, cp);
        if (!ctx) { LOGW("context init failed"); llama_model_free(model); return 0; }

        auto *s = new Session();
        s->model = model;
        s->ctx = ctx;
        s->vocab = llama_model_get_vocab(model);
        LOGI("model loaded: ctx=%d threads=%d", n_ctx, n_threads);
        return reinterpret_cast<jlong>(s);
    } catch (const std::exception &e) {
        LOGW("model load failed: %s", e.what());
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
        return 0;
    } catch (...) {
        LOGW("model load failed");
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ai_nova_app_llm_LlamaNative_stop(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s) s->stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_nova_app_llm_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    try {
        if (s->ctx) llama_free(s->ctx);
        if (s->model) llama_model_free(s->model);
    } catch (const std::exception &e) {
        LOGW("free failed: %s", e.what());
    } catch (...) {
        LOGW("free failed");
    }
    delete s;
}

// messages: [role0, text0, role1, text1, …]; listener.onToken(String) → false = остановить.
namespace {

// Внутренняя часть generate. Любое C++ исключение llama.cpp (нехватка памяти, некорректный батч)
// перехватывается в JNI-обёртке ниже: иначе std::terminate → SIGABRT → приложение закрывается.
jstring generate_impl(JNIEnv *env, jlong handle, jobjectArray jmessages, jint max_tokens,
                      jfloat temperature, jobject listener) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return env->NewStringUTF("");
    s->stop = false;

    // Сообщения диалога и шаблон модели (у Qwen — ChatML).
    std::vector<std::string> roles, texts;
    const jsize n = env->GetArrayLength(jmessages);
    for (jsize i = 0; i + 1 < n; i += 2) {
        auto role = (jstring) env->GetObjectArrayElement(jmessages, i);
        auto text = (jstring) env->GetObjectArrayElement(jmessages, i + 1);
        roles.push_back(jstr(env, role));
        texts.push_back(jstr(env, text));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(text);
    }
    std::vector<llama_chat_message> msgs;
    for (size_t i = 0; i < roles.size(); i++) msgs.push_back({roles[i].c_str(), texts[i].c_str()});
    std::string prompt;
    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl) {
        std::vector<char> buf(8192);
        int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
        if (len > (int32_t) buf.size()) {
            buf.resize(len + 1);
            len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
        }
        if (len > 0) prompt.assign(buf.data(), len);
    }
    if (prompt.empty()) prompt = chatml(roles, texts);
    disable_thinking_if_needed(s->model, prompt);

    // Токены запроса.
    const int32_t n_prompt = -llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt > 0 ? n_prompt : 0);
    if (n_prompt <= 0 || llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_prompt, true, true) < 0) {
        return env->NewStringUTF("");
    }
    const int32_t n_ctx = (int32_t) llama_n_ctx(s->ctx);
    // Длинный вопрос: ответ укорачиваем, но не молчим; совсем не помещается — пусто (Kotlin ответит без модели).
    if (n_prompt + max_tokens + 8 > n_ctx) max_tokens = n_ctx - n_prompt - 8;
    if (max_tokens < 32) {
        LOGW("prompt too long: %d", n_prompt);
        return env->NewStringUTF("");
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);
    for (int32_t i = 0; i < n_prompt; i += 512) {
        const int32_t chunk = std::min<int32_t>(512, n_prompt - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(s->ctx, batch) != 0) { LOGW("decode prompt failed"); return env->NewStringUTF(""); }
    }

    SamplerGuard sampler;
    sampler.smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler *smpl = sampler.smpl;
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cls = listener ? env->GetObjectClass(listener) : nullptr;
    jmethodID onToken = cls ? env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z") : nullptr;
    // Метода может не быть (например, минификатор переименовал колбэк). Тогда JNI оставляет
    // «висящее» исключение, и следующий же вызов FindClass убивает процесс по SIGABRT —
    // ответ важнее потоковой выдачи, поэтому просто говорим без колбэка.
    if (!onToken && env->ExceptionCheck()) env->ExceptionClear();

    std::string out, pending;
    std::vector<char> piece(256);
    for (int32_t i = 0; i < max_tokens && !s->stop; i++) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;
        int32_t len = llama_token_to_piece(s->vocab, tok, piece.data(), (int32_t) piece.size(), 0, false);
        if (len < 0) {
            // Кусок длиннее буфера: llama.cpp сообщает нужный размер со знаком минус.
            piece.resize(-len);
            len = llama_token_to_piece(s->vocab, tok, piece.data(), (int32_t) piece.size(), 0, false);
        }
        if (len > 0) {
            pending.append(piece.data(), len);
            const size_t ready = complete_utf8_prefix(pending);
            if (ready > 0) {
                std::string part = pending.substr(0, ready);
                pending.erase(0, ready);
                out += part;
                if (onToken) {
                    jstring js = to_jstring(env, part);
                    const jboolean go = env->CallBooleanMethod(listener, onToken, js);
                    env->DeleteLocalRef(js);
                    if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
                    if (!go) break;
                }
            }
        }
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, next) != 0) break;
    }
    if (cls) env->DeleteLocalRef(cls);
    if (!pending.empty() && complete_utf8_prefix(pending) == pending.size()) out += pending;
    return to_jstring(env, out);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ai_nova_app_llm_LlamaNative_generate(JNIEnv *env, jclass, jlong handle, jobjectArray jmessages, jint max_tokens,
                                          jfloat temperature, jobject listener) {
    try {
        return generate_impl(env, handle, jmessages, max_tokens, temperature, listener);
    } catch (const std::exception &e) {
        LOGW("generate failed: %s", e.what());
    } catch (...) {
        LOGW("generate failed");
    }
    return env->NewStringUTF("");
}
