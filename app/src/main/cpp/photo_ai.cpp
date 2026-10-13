#include <jni.h>
#include <atomic>
#include <algorithm>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {
struct Job { std::atomic<bool> cancelled{false}; };
std::once_flag backend_once;
bool should_abort(void * value) { return static_cast<Job *>(value)->cancelled.load(); }
bool keep_loading(float, void * value) { return !should_abort(value); }
void check_cancel(Job * job) {
    if (job->cancelled.load()) throw std::runtime_error("CANCELLED");
}

class UtfChars {
    JNIEnv * env;
    jstring value;
    const char * data;
public:
    UtfChars(JNIEnv * e, jstring s) : env(e), value(s), data(e->GetStringUTFChars(s, nullptr)) {
        if (!data) throw std::runtime_error("JNI_STRING");
    }
    ~UtfChars() { env->ReleaseStringUTFChars(value, data); }
    const char * get() const { return data; }
};
jbyteArray bytes(JNIEnv * env, const std::string & value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (!result) throw std::runtime_error("JNI_ALLOCATION");
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()),
                           reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
void progress(JNIEnv * env, jobject listener, jmethodID method, int stage, const std::string & text) {
    auto data = bytes(env, text);
    env->CallVoidMethod(listener, method, stage, data);
    env->DeleteLocalRef(data);
    if (env->ExceptionCheck()) throw std::runtime_error("JAVA_CALLBACK");
}
template <typename T, void (*Release)(T *)>
using Owned = std::unique_ptr<T, decltype(Release)>;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_photoprompt_offline_NativeBridge_newJob(JNIEnv * env, jclass) {
    try { return reinterpret_cast<jlong>(new Job()); }
    catch (...) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "JOB_ALLOCATION");
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_app_photoprompt_offline_NativeBridge_cancel(JNIEnv *, jclass, jlong handle) {
    if (handle) reinterpret_cast<Job *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_app_photoprompt_offline_NativeBridge_freeJob(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Job *>(handle);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_photoprompt_offline_NativeBridge_generate(
        JNIEnv * env, jclass, jlong handle, jstring model_path, jstring projector_path,
        jbyteArray pixels, jint width, jint height, jstring instruction, jobject listener) {
    try {
        auto * job = reinterpret_cast<Job *>(handle);
        if (!job || !pixels || !listener || !model_path || !projector_path || !instruction)
            throw std::runtime_error("INVALID_INPUT");
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024 ||
                env->GetArrayLength(pixels) != static_cast<jsize>(width * height * 3))
            throw std::runtime_error("INVALID_IMAGE");
        auto cls = env->GetObjectClass(listener);
        auto callback = env->GetMethodID(cls, "onProgress", "(I[B)V");
        env->DeleteLocalRef(cls);
        if (!callback) throw std::runtime_error("JNI_CALLBACK");
        check_cancel(job);
        std::call_once(backend_once, [] { llama_backend_init(); });

        UtfChars model_file(env, model_path);
        UtfChars projector_file(env, projector_path);
        UtfChars question(env, instruction);
        progress(env, listener, callback, 1, "");
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.progress_callback = keep_loading;
        model_params.progress_callback_user_data = job;
        Owned<llama_model, llama_model_free> model(
            llama_model_load_from_file(model_file.get(), model_params), llama_model_free);
        check_cancel(job);
        if (!model) throw std::runtime_error("MODEL_LOAD_FAILED");

        auto ctx_params = llama_context_default_params();
        ctx_params.n_ctx = 2048;
        ctx_params.n_batch = 256;
        ctx_params.n_ubatch = 256;
        ctx_params.n_threads = 4;
        ctx_params.n_threads_batch = 4;
        ctx_params.offload_kqv = false;
        ctx_params.op_offload = false;
        ctx_params.abort_callback = should_abort;
        ctx_params.abort_callback_data = job;
        Owned<llama_context, llama_free> ctx(llama_init_from_model(model.get(), ctx_params), llama_free);
        if (!ctx) throw std::runtime_error("CONTEXT_LOAD_FAILED");

        progress(env, listener, callback, 2, "");
        auto vision_params = mtmd_context_params_default();
        vision_params.use_gpu = false;
        vision_params.n_threads = 4;
        vision_params.warmup = false;
        vision_params.image_max_tokens = 256;
        vision_params.progress_callback = keep_loading;
        vision_params.progress_callback_user_data = job;
        Owned<mtmd_context, mtmd_free> vision(
            mtmd_init_from_file(projector_file.get(), model.get(), vision_params), mtmd_free);
        check_cancel(job);
        if (!vision || !mtmd_support_vision(vision.get())) throw std::runtime_error("PROJECTOR_LOAD_FAILED");

        std::vector<unsigned char> rgb(static_cast<size_t>(width * height * 3));
        env->GetByteArrayRegion(pixels, 0, static_cast<jsize>(rgb.size()), reinterpret_cast<jbyte *>(rgb.data()));
        if (env->ExceptionCheck()) throw std::runtime_error("JNI_PIXELS");
        Owned<mtmd_bitmap, mtmd_bitmap_free> bitmap(
            mtmd_bitmap_init(width, height, rgb.data()), mtmd_bitmap_free);
        if (!bitmap) throw std::runtime_error("IMAGE_LOAD_FAILED");
        struct Question { const char * title; std::string prompt; };
        const std::vector<Question> questions = {
            {"Subject and setting", question.get()},
            {"Composition and viewpoint", "Describe the framing, camera angle, subject placement and perspective in this image. Answer in English in two or three sentences."},
            {"Lighting and shadows", "Describe the visible lighting, direction and softness of the light, highlights and shadows in this image. State uncertainty. Answer in English in two or three sentences."},
            {"Color and mood", "Describe the main colors, color temperature, contrast and mood of this image in English in two or three sentences."},
            {"Focus and lens impression", "Describe the sharp and blurred areas, depth of field and apparent perspective. Do not name an exact lens or camera. Answer in English in two or three sentences."},
            {"Style and processing", "Describe the visual style, photographic genre, textures and apparent processing of this image. Do not claim who created it. Answer in English in two or three sentences."}
        };
        std::string output = "ANALYSIS:\n";
        std::string prompt;
        std::string finish = "complete";
        const llama_vocab * vocab = llama_model_get_vocab(model.get());
        const mtmd_bitmap * images[] = {bitmap.get()};
        for (const auto & item : questions) {
            check_cancel(job);
            // Independent short questions are more reliable for this small model
            // than asking it to obey a long schema. Keep weights loaded between them.
            llama_memory_clear(llama_get_memory(ctx.get()), true);
            std::string content = std::string(mtmd_default_marker()) + "\n" + item.prompt;
            llama_chat_message chat = {"user", content.c_str()};
            int length = llama_chat_apply_template("smolvlm", &chat, 1, true, nullptr, 0);
            if (length <= 0) throw std::runtime_error("CHAT_TEMPLATE_FAILED");
            std::vector<char> formatted(static_cast<size_t>(length) + 1, '\0');
            int written = llama_chat_apply_template("smolvlm", &chat, 1, true, formatted.data(), length);
            if (written != length) throw std::runtime_error("CHAT_TEMPLATE_FAILED");
            mtmd_input_text input{};
            input.text = formatted.data();
            input.text_len = static_cast<size_t>(length);
            input.add_special = true;
            input.parse_special = true;
            Owned<mtmd_input_chunks, mtmd_input_chunks_free> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
            if (!chunks || mtmd_tokenize(vision.get(), chunks.get(), &input, images, 1) != 0)
                throw std::runtime_error("IMAGE_TOKENIZE_FAILED");
            auto positions = mtmd_helper_get_n_pos(chunks.get());
            constexpr int max_tokens = 160;
            if (positions < 0 || positions + max_tokens + 4 >= static_cast<llama_pos>(llama_n_ctx(ctx.get())))
                throw std::runtime_error("IMAGE_TOO_COMPLEX");
            output += std::string(item.title) + ":\n";
            progress(env, listener, callback, 3, output);
            llama_pos past = 0;
            int evaluated = mtmd_helper_eval_chunks(vision.get(), ctx.get(), chunks.get(), 0, 0,
                                                    256, true, &past);
            check_cancel(job);
            if (evaluated != 0) throw std::runtime_error("IMAGE_EVAL_FAILED");
            chunks.reset();
            Owned<llama_sampler, llama_sampler_free> sampler(
                llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
            if (!sampler) throw std::runtime_error("SAMPLER_FAILED");
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.9f, 1));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.2f));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(42));
            std::string answer;
            bool complete = false;
            for (int i = 0; i < max_tokens; ++i) {
                check_cancel(job);
                auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
                if (llama_vocab_is_eog(vocab, token)) { complete = true; break; }
                char local[256];
                int n = llama_token_to_piece(vocab, token, local, sizeof(local), 0, false);
                if (n < 0) {
                    std::vector<char> expanded(static_cast<size_t>(-n));
                    n = llama_token_to_piece(vocab, token, expanded.data(), expanded.size(), 0, false);
                    if (n < 0) throw std::runtime_error("TOKEN_DECODE_FAILED");
                    answer.append(expanded.data(), static_cast<size_t>(n));
                } else answer.append(local, static_cast<size_t>(n));
                if (i % 8 == 0) progress(env, listener, callback, 4, output + answer);
                if (i + 1 < max_tokens) {
                    int result = llama_decode(ctx.get(), llama_batch_get_one(&token, 1));
                    check_cancel(job);
                    if (result != 0) throw std::runtime_error("TEXT_EVAL_FAILED");
                }
            }
            if (!complete) finish = "length";
            if (answer.find_first_not_of(" \t\r\n") == std::string::npos) throw std::runtime_error("EMPTY_OUTPUT");
            output += answer + "\n\n";
            // Compose the copyable prompt from the actual six visual answers.
            // There is no generic fallback scene or invented photography metadata.
            std::string flat;
            bool space = true;
            for (unsigned char c : answer) {
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    if (!space) flat += ' ';
                    space = true;
                } else { flat += static_cast<char>(c); space = false; }
            }
            while (!flat.empty() && flat.back() == ' ') flat.pop_back();
            if (!prompt.empty()) prompt += ' ';
            prompt += std::string(item.title) + ": " + flat;
            if (!flat.empty() && flat.back() != '.' && flat.back() != '!' && flat.back() != '?') prompt += '.';
        }
        check_cancel(job);
        output += "PROMPT:\n" + prompt;
        progress(env, listener, callback, 4, output);
        auto byte_class = env->FindClass("[B");
        auto result = env->NewObjectArray(2, byte_class, nullptr);
        env->DeleteLocalRef(byte_class);
        if (!result) throw std::runtime_error("JNI_ALLOCATION");
        auto text_bytes = bytes(env, output);
        auto finish_bytes = bytes(env, finish);
        env->SetObjectArrayElement(result, 0, text_bytes);
        env->SetObjectArrayElement(result, 1, finish_bytes);
        env->DeleteLocalRef(text_bytes);
        env->DeleteLocalRef(finish_bytes);
        return result;
    } catch (const std::exception & error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/RuntimeException"), error.what());
        return nullptr;
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "NATIVE_FAILURE");
        return nullptr;
    }
}
