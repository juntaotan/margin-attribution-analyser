// JNI transport only: all numerical operations remain in the existing native engines.
#include <jni.h>
#include <algorithm>
#include <fstream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#ifdef MARGIN_CUDA
#include "fgw_engine.cuh"
#include "bom_upward_engine.cuh"
#include <cuda_runtime.h>
using FgwSession = fgw::CudaFgwSession;
#else
#include "fgw_engine.hpp"
#include "bom_upward_engine.hpp"
using FgwSession = fgw::OpenClFgwSession;
#endif

namespace {
constexpr const char* package = "dev/margintrace/margin_attribution_backend/algorithm/FGW/FgwEngine$";
struct Frame {
    JNIEnv* env;
    explicit Frame(JNIEnv* e) : env(e) { if (e->PushLocalFrame(64) < 0) throw std::bad_alloc(); }
    ~Frame() { if (env) env->PopLocalFrame(nullptr); }
    jobject result(jobject value) { jobject out = env->PopLocalFrame(value); env = nullptr; return out; }
};
void pending(JNIEnv* e) { if (e->ExceptionCheck()) throw std::runtime_error("JNI transport failed"); }
jclass type(JNIEnv* e, const char* suffix) {
    auto c = e->FindClass((std::string(package) + suffix).c_str()); pending(e); return c;
}
jobject make(JNIEnv* e, const char* suffix) {
    auto c = type(e, suffix); auto constructor = e->GetMethodID(c, "<init>", "()V"); pending(e);
    auto out = e->NewObject(c, constructor); pending(e); return out;
}
jfieldID field(JNIEnv* e, jobject o, const char* name, const char* signature) {
    auto c = e->GetObjectClass(o); auto id = e->GetFieldID(c, name, signature); e->DeleteLocalRef(c); pending(e); return id;
}
jobject get(JNIEnv* e, jobject o, const char* name, const char* signature) {
    auto c = e->GetObjectClass(o); auto id = e->GetMethodID(c, name, signature); pending(e);
    auto out = e->CallObjectMethod(o, id); e->DeleteLocalRef(c); pending(e); return out;
}
std::string text(JNIEnv* e, jstring s) {
    if (!s) return {};
    const char* p = e->GetStringUTFChars(s, nullptr); pending(e);
    std::string out(p); e->ReleaseStringUTFChars(s, p); return out;
}
std::string textField(JNIEnv* e, jobject o, const char* name) {
    auto s = static_cast<jstring>(get(e, o, name, "()Ljava/lang/String;")); auto out = text(e, s); e->DeleteLocalRef(s); return out;
}
template<class T> std::vector<T> ints(JNIEnv* e, jintArray a) {
    std::vector<jint> data(e->GetArrayLength(a)); e->GetIntArrayRegion(a, 0, data.size(), data.data()); pending(e);
    return {data.begin(), data.end()};
}
std::vector<double> doubles(JNIEnv* e, jdoubleArray a) {
    std::vector<double> out(e->GetArrayLength(a)); e->GetDoubleArrayRegion(a, 0, out.size(), out.data()); pending(e); return out;
}
std::vector<std::string> strings(JNIEnv* e, jobject list) {
    auto c = e->FindClass("java/util/List"); auto size = e->GetMethodID(c, "size", "()I"); auto at = e->GetMethodID(c, "get", "(I)Ljava/lang/Object;");
    int n = e->CallIntMethod(list, size); pending(e); std::vector<std::string> out;
    for (int i = 0; i < n; ++i) { auto s = static_cast<jstring>(e->CallObjectMethod(list, at, i)); pending(e); out.push_back(text(e, s)); e->DeleteLocalRef(s); }
    e->DeleteLocalRef(c); return out;
}
fgw::Graph graph(JNIEnv* e, jobject value) {
    Frame frame(e); fgw::Graph g;
    g.productId = textField(e, value, "productId"); g.instanceId = textField(e, value, "instanceId");
    auto c = e->GetObjectClass(value); g.rootIndex = e->CallIntMethod(value, e->GetMethodID(c, "rootIndex", "()I")); pending(e);
    g.rowOffsets = ints<int>(e, static_cast<jintArray>(get(e, value, "rowOffsets", "()[I")));
    g.colIndices = ints<int>(e, static_cast<jintArray>(get(e, value, "colIndices", "()[I")));
    g.embeddings = doubles(e, static_cast<jdoubleArray>(get(e, value, "embeddings", "()[D")));
    auto list = get(e, value, "sources", "()Ljava/util/List;"); auto lc = e->FindClass("java/util/List");
    auto at = e->GetMethodID(lc, "get", "(I)Ljava/lang/Object;"); int n = e->CallIntMethod(list, e->GetMethodID(lc, "size", "()I")); pending(e);
    for (int i = 0; i < n; ++i) {
        Frame nodeFrame(e); auto node = e->CallObjectMethod(list, at, i); pending(e); fgw::NodeSource s;
        s.occurrenceId = textField(e, node, "occurrenceId"); s.materialId = textField(e, node, "materialId");
        s.productionOrderId = textField(e, node, "productionOrderId"); s.quantity = textField(e, node, "quantity"); s.amount = textField(e, node, "amount");
        s.issueLineIds = strings(e, get(e, node, "issueLineIds", "()Ljava/util/List;"));
        s.fifoAllocationIds = strings(e, get(e, node, "fifoAllocationIds", "()Ljava/util/List;")); g.sources.push_back(std::move(s));
    }
    return g;
}
fgw::Config config(JNIEnv* e, jobject value) {
    fgw::Config c;
#define DOUBLE_INPUT(name) c.name = e->GetDoubleField(value, field(e, value, #name, "D"))
    DOUBLE_INPUT(alpha); DOUBLE_INPUT(tolAbs); DOUBLE_INPUT(tolRel); DOUBLE_INPUT(tolGapAbs); DOUBLE_INPUT(tolGapRel);
    DOUBLE_INPUT(costScaleFloor); DOUBLE_INPUT(feasibilityTolerance); DOUBLE_INPUT(numericalTolerance); DOUBLE_INPUT(otTolerance);
#undef DOUBLE_INPUT
    c.maxIterations = e->GetIntField(value, field(e, value, "maxIterations", "I"));
#define BOOL_INPUT(name) c.name = e->GetBooleanField(value, field(e, value, #name, "Z"))
    BOOL_INPUT(captureTrace); BOOL_INPUT(incrementalOtTree); BOOL_INPUT(candidateOtPricing);
#undef BOOL_INPUT
    // The Java workflow submits/consumes one pair at a time; retain bounded native resources.
    c.maxConcurrentComparisons = 1; c.gpuSlots = 1; c.dispatcherThreads = 1; pending(e); return c;
}
void array(JNIEnv* e, jobject o, const char* name, const std::vector<double>& data) {
    auto a = e->NewDoubleArray(data.size()); pending(e); e->SetDoubleArrayRegion(a, 0, data.size(), data.data()); pending(e);
    e->SetObjectField(o, field(e, o, name, "[D"), a); e->DeleteLocalRef(a);
}
void number(JNIEnv* e, jobject o, const char* name, double value) { e->SetDoubleField(o, field(e, o, name, "D"), value); }
void integer(JNIEnv* e, jobject o, const char* name, jint value) { e->SetIntField(o, field(e, o, name, "I"), value); }
void string(JNIEnv* e, jobject o, const char* name, const std::string& value) {
    auto s = e->NewStringUTF(value.c_str()); pending(e); e->SetObjectField(o, field(e, o, name, "Ljava/lang/String;"), s); e->DeleteLocalRef(s);
}
void object(JNIEnv* e, jobject o, const char* name, const char* suffix, jobject value) {
    e->SetObjectField(o, field(e, o, name, ("L" + std::string(package) + suffix + ";").c_str()), value);
}
jobject costs(JNIEnv* e, const fgw::Costs& c) {
    auto t = type(e, "Costs"); return e->NewObject(t, e->GetMethodID(t, "<init>", "(DDD)V"), c.material, c.structure, c.fgw);
}
jobject iteration(JNIEnv* e, const fgw::Iteration& t) {
    Frame frame(e); auto o = make(e, "Iteration"); integer(e, o, "updates", t.updates);
    object(e, o, "costs", "Costs", costs(e, t.costs)); number(e, o, "gap", t.gap);
    number(e, o, "absoluteCostChange", t.absoluteCostChange); number(e, o, "relativeCostChange", t.relativeCostChange);
    auto mc = type(e, "MarginalErrors"); auto m = e->NewObject(mc, e->GetMethodID(mc, "<init>", "(DDDDZ)V"), t.marginals.row, t.marginals.column, t.marginals.total, t.marginals.minimum, t.marginals.finite);
    object(e, o, "marginals", "MarginalErrors", m);
    auto sc = type(e, "LineSearch"); auto s = e->NewObject(sc, e->GetMethodID(sc, "<init>", "(DDD)V"), t.step.a, t.step.b, t.step.gamma); object(e, o, "step", "LineSearch", s);
    array(e, o, "correspondence", t.correspondence); array(e, o, "gradient", t.gradient); array(e, o, "direction", t.direction);
    auto ot = make(e, "LinearOtResult"); array(e, ot, "correspondence", t.ot.correspondence);
    number(e, ot, "linearCost", t.ot.linearCost); number(e, ot, "dualCost", t.ot.dualCost); number(e, ot, "maximumReducedCostViolation", t.ot.maximumReducedCostViolation);
#define COUNTER(name) e->SetLongField(ot, field(e, ot, #name, "J"), t.ot.name)
    COUNTER(pivots); COUNTER(degeneratePivots); COUNTER(fullPricingScans); COUNTER(candidatePricingPasses); COUNTER(treeRebuilds);
    COUNTER(incrementalTreeUpdates); COUNTER(treeNodesVisited); COUNTER(cycleEdges);
#undef COUNTER
    e->SetBooleanField(ot, field(e, ot, "usedBlandRule", "Z"), t.ot.usedBlandRule); object(e, o, "ot", "LinearOtResult", ot); pending(e); return frame.result(o);
}
jobject result(JNIEnv* e, const fgw::Result& r, const std::string& execution) {
    Frame frame(e); auto o = make(e, "Result"); integer(e, o, "rows", r.rows); integer(e, o, "columns", r.columns); integer(e, o, "bestStart", r.bestStart);
    number(e, o, "totalMilliseconds", r.totalMilliseconds); string(e, o, "execution", execution);
    array(e, o, "p", r.p); array(e, o, "q", r.q); array(e, o, "materialDistances", r.materialDistances);
    array(e, o, "leftDistances", r.leftDistances); array(e, o, "rightDistances", r.rightDistances);
    auto times = make(e, "ExecutionTimes"); number(e, times, "cpuOtMilliseconds", r.executionTimes.cpuOtMilliseconds);
    number(e, times, "gpuOtMilliseconds", r.executionTimes.gpuOtMilliseconds); number(e, times, "deviceMilliseconds", r.executionTimes.deviceMilliseconds);
    number(e, times, "synchronizationMilliseconds", r.executionTimes.synchronizationMilliseconds); object(e, o, "executionTimes", "ExecutionTimes", times);
    auto lc = e->FindClass("java/util/List"); auto add = e->GetMethodID(lc, "add", "(Ljava/lang/Object;)Z");
    auto list = e->GetObjectField(o, field(e, o, "starts", "Ljava/util/List;"));
    for (const auto& start : r.starts) {
        Frame startFrame(e); auto s = make(e, "StartResult"); array(e, s, "correspondence", start.correspondence);
        object(e, s, "costs", "Costs", costs(e, start.costs)); number(e, s, "finalGap", start.finalGap); integer(e, s, "iterations", start.iterations);
        string(e, s, "diagnostic", start.diagnostic);
        auto status = type(e, "Status"); auto enumValue = e->GetStaticObjectField(status, e->GetStaticFieldID(status, fgw::statusName(start.status), ("L" + std::string(package) + "Status;").c_str()));
        object(e, s, "status", "Status", enumValue);
        auto trace = e->GetObjectField(s, field(e, s, "trace", "Ljava/util/List;"));
        for (const auto& state : start.trace) { auto item = iteration(e, state); e->CallBooleanMethod(trace, add, item); e->DeleteLocalRef(item); pending(e); }
        e->CallBooleanMethod(list, add, s); pending(e);
    }
    pending(e); return frame.result(o);
}
struct Session {
    fgw::Config config;
    int rows;
    std::unique_ptr<FgwSession> engine;
    Session(fgw::Graph g, fgw::Config c) : config(c), rows(g.nodeCount()), engine(std::make_unique<FgwSession>(std::move(g), c)) {}
};
int index(const std::string& value) {
    if (value.empty() || !std::all_of(value.begin(), value.end(), [](char c) { return c >= '0' && c <= '9'; })) throw std::invalid_argument("Invalid device index");
    std::size_t used; int out = std::stoi(value, &used); return out;
}
#ifndef MARGIN_CUDA
std::pair<int,int> indices(const std::string& value) {
    auto colon = value.find(':'); if (colon == std::string::npos) return {0, index(value)};
    return {index(value.substr(0, colon)), index(value.substr(colon + 1))};
}
cl::Device openclDevice(const std::string& value) {
    auto [p,d] = indices(value); std::vector<cl::Platform> platforms; cl::Platform::get(&platforms);
    if (p >= static_cast<int>(platforms.size())) throw std::runtime_error("OpenCL platform unavailable");
    std::vector<cl::Device> devices; platforms[p].getDevices(CL_DEVICE_TYPE_ALL, &devices);
    if (d >= static_cast<int>(devices.size())) throw std::runtime_error("OpenCL device unavailable"); return devices[d];
}
#endif
void fail(JNIEnv* e, const char* typeName, const char* message) {
    if (!e->ExceptionCheck()) { auto c = e->FindClass(typeName); if (c) e->ThrowNew(c, message); }
}
void translate(JNIEnv* e) {
    try { throw; }
    catch (const std::invalid_argument& error) { fail(e, "java/lang/IllegalArgumentException", error.what()); }
    catch (const std::bad_alloc&) { fail(e, "java/lang/OutOfMemoryError", "Native acceleration allocation failed"); }
#ifndef MARGIN_CUDA
    catch (const cl::Error& error) { std::string message = std::string(error.what()) + ": " + std::to_string(error.err()); fail(e, "dev/margintrace/margin_attribution_backend/infrastructure/acceleration/NativeExecutionException", message.c_str()); }
#endif
    catch (const std::exception& error) { fail(e, "dev/margintrace/margin_attribution_backend/infrastructure/acceleration/NativeExecutionException", error.what()); }
}
jobjectArray devices(JNIEnv* e, jclass) {
    try {
        Frame frame(e); std::vector<std::vector<std::string>> rows;
#ifdef MARGIN_CUDA
        int n = 0; auto status = cudaGetDeviceCount(&n); if (status != cudaSuccess) throw std::runtime_error(cudaGetErrorString(status));
        for (int d = 0; d < n; ++d) { cudaDeviceProp prop; status = cudaGetDeviceProperties(&prop, d); if (status != cudaSuccess) throw std::runtime_error(cudaGetErrorString(status)); rows.push_back({std::to_string(d), prop.name, prop.major >= 2 ? "true" : "false"}); }
#else
        std::vector<cl::Platform> platforms; cl::Platform::get(&platforms);
        for (std::size_t p = 0; p < platforms.size(); ++p) {
            std::vector<cl::Device> list; platforms[p].getDevices(CL_DEVICE_TYPE_ALL, &list);
            for (std::size_t d = 0; d < list.size(); ++d) if (list[d].getInfo<CL_DEVICE_TYPE>() & CL_DEVICE_TYPE_GPU) {
                rows.push_back({std::to_string(p) + ":" + std::to_string(d), list[d].getInfo<CL_DEVICE_NAME>(), list[d].getInfo<CL_DEVICE_DOUBLE_FP_CONFIG>() ? "true" : "false"});
            }
        }
#endif
        auto strings = e->FindClass("[Ljava/lang/String;"); auto stringType = e->FindClass("java/lang/String");
        auto out = e->NewObjectArray(rows.size(), strings, nullptr); pending(e);
        for (std::size_t i = 0; i < rows.size(); ++i) { Frame rowFrame(e); auto row = e->NewObjectArray(3, stringType, nullptr); pending(e);
            for (int k = 0; k < 3; ++k) { auto value = e->NewStringUTF(rows[i][k].c_str()); e->SetObjectArrayElement(row, k, value); pending(e); }
            e->SetObjectArrayElement(out, i, row); pending(e);
        }
        return static_cast<jobjectArray>(frame.result(out));
    } catch (...) { translate(e); return nullptr; }
}
// Validate array and DAG boundaries before submitting indices to a device kernel.
void validateFrontier(const BomUpwardGraph& g) {
    const auto n = g.node_values.size();
    auto require = [](bool ok) { if (!ok) throw std::invalid_argument("Invalid frontier CSR/DAG/terminal boundary"); };
    require(g.offsets.size() == n + 1 && g.reverse_offsets.size() == n + 1 && g.node_comparable.size() == n);
    require(g.offsets.front() == 0 && g.reverse_offsets.front() == 0 && g.reverse_offsets.back() == g.reverse_successors.size());
    std::vector<std::size_t> children(n, 0), remaining(n, 0); std::vector<uint32_t> queue;
    for (std::size_t i = 0; i < n; ++i) {
        require(g.offsets[i] <= g.offsets[i+1] && g.reverse_offsets[i] <= g.reverse_offsets[i+1]);
        require(g.reverse_offsets[i+1] <= g.reverse_successors.size());
        for (auto k = g.reverse_offsets[i]; k < g.reverse_offsets[i+1]; ++k) {
            require(g.reverse_successors[k] < n); ++children[g.reverse_successors[k]];
        }
    }
    for (std::size_t i = 0; i < n; ++i) {
        require(g.offsets[i+1] - g.offsets[i] <= children[i]); remaining[i] = children[i];
        if (!remaining[i]) queue.push_back(i);
    }
    for (std::size_t head = 0; head < queue.size(); ++head) {
        auto v = queue[head];
        for (auto k = g.reverse_offsets[v]; k < g.reverse_offsets[v+1]; ++k) if (--remaining[g.reverse_successors[k]] == 0) queue.push_back(g.reverse_successors[k]);
    }
    require(queue.size() == n); std::vector<bool> seen(n, false);
    queue.clear();
    for (auto t : g.terminal_nodes) { require(t < n && !seen[t]); seen[t] = true; queue.push_back(t); }
    // Java preparation retains all reverse edges but counts only seed-reachable children.
    // Full child counts are also valid: unseeded children deliberately block their parents.
    std::vector<std::size_t> reachable(n, 0);
    for (std::size_t head = 0; head < queue.size(); ++head) {
        auto v = queue[head];
        for (auto k = g.reverse_offsets[v]; k < g.reverse_offsets[v+1]; ++k) {
            auto parent = g.reverse_successors[k]; ++reachable[parent];
            if (!seen[parent]) { seen[parent] = true; queue.push_back(parent); }
        }
    }
    for (std::size_t i = 0; i < n; ++i) require(g.offsets[i+1] - g.offsets[i] >= reachable[i]);
}
jintArray frontier(JNIEnv* e, jclass, jstring device, jstring kernels, jintArray offsets, jintArray reverse, jintArray successors, jintArray terminals, jlongArray values, jbyteArray comparable, jlong threshold) {
    try {
        Frame frame(e); BomUpwardGraph g;
        g.offsets = ints<uint32_t>(e, offsets); g.reverse_offsets = ints<uint32_t>(e, reverse); g.reverse_successors = ints<uint32_t>(e, successors); g.terminal_nodes = ints<uint32_t>(e, terminals);
        std::vector<jlong> v(e->GetArrayLength(values)); e->GetLongArrayRegion(values, 0, v.size(), v.data()); g.node_values.assign(v.begin(), v.end());
        std::vector<jbyte> b(e->GetArrayLength(comparable)); e->GetByteArrayRegion(comparable, 0, b.size(), b.data()); g.node_comparable.assign(b.begin(), b.end()); pending(e);
        validateFrontier(g);
        // No parent can be selected in an edgeless DAG; avoid zero-byte OpenCL buffers.
        if (g.reverse_successors.empty()) return static_cast<jintArray>(frame.result(e->NewIntArray(0)));
        std::vector<uint32_t> selected;
#ifdef MARGIN_CUDA
        auto status = cudaSetDevice(index(text(e, device))); if (status != cudaSuccess) throw std::runtime_error(cudaGetErrorString(status));
        selected = CudaBomUpwardEngine().run(g, static_cast<uint64_t>(threshold));
#else
        auto d = openclDevice(text(e, device)); cl::Context context(d); cl::CommandQueue queue(context, d);
        std::ifstream input(text(e, kernels) + "/bom_upward.cl"); if (!input) throw std::runtime_error("Cannot read BOM OpenCL kernel");
        std::string source((std::istreambuf_iterator<char>(input)), {}); cl::Program program(context, source); program.build({d});
        selected = BomUpwardEngine(context, queue, program).run(g, static_cast<uint64_t>(threshold));
#endif
        std::vector<jint> output(selected.begin(), selected.end()); auto out = e->NewIntArray(output.size()); pending(e); e->SetIntArrayRegion(out, 0, output.size(), output.data()); pending(e);
        return static_cast<jintArray>(frame.result(out));
    } catch (...) { translate(e); return nullptr; }
}
jlong create(JNIEnv* e, jclass, jstring device, jstring kernels, jobject baseline, jobject settings) {
    try {
        Frame frame(e); auto c = config(e, settings);
#ifdef MARGIN_CUDA
        c.device = index(text(e, device));
#else
        auto [p,d] = indices(text(e, device)); c.platform = p; c.device = d; c.kernelPath = text(e, kernels) + "/fgw.cl";
#endif
        return reinterpret_cast<jlong>(new Session(graph(e, baseline), c));
    } catch (...) { translate(e); return 0; }
}
jobject compare(JNIEnv* e, jclass, jlong handle, jobject target, jobjectArray starts) {
    try {
        Frame frame(e); auto& s = *reinterpret_cast<Session*>(handle); auto g = graph(e, target); int columns = g.nodeCount();
        std::vector<std::vector<double>> matrices;
        for (int i = 0; i < e->GetArrayLength(starts); ++i) { auto a = static_cast<jdoubleArray>(e->GetObjectArrayElement(starts, i)); pending(e); matrices.push_back(doubles(e, a)); e->DeleteLocalRef(a); }
        auto r = s.engine->submit(std::move(g), std::move(matrices)).get();
#ifdef MARGIN_CUDA
        std::string execution = s.config.captureTrace ? "EXACT_CPU_OT_FALLBACK: captureTrace" : s.rows + columns > 320 ? "EXACT_CPU_OT_FALLBACK: N+M exceeds 320" : "CUDA_DEVICE_EXACT_SIMPLEX";
#else
        std::string execution = r.otExecution;
#endif
        return frame.result(result(e, r, execution));
    } catch (...) { translate(e); return nullptr; }
}
void close(JNIEnv* e, jclass, jlong handle) {
    try { std::unique_ptr<Session> s(reinterpret_cast<Session*>(handle)); s->engine->close(); }
    catch (...) { translate(e); }
}
} // namespace

// Each family registers only its own Java holder, avoiding cross-library native symbol collisions.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* e; if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
#ifdef MARGIN_CUDA
    const char* holder = "dev/margintrace/margin_attribution_backend/infrastructure/acceleration/NativeBindings$Cuda";
#else
    const char* holder = "dev/margintrace/margin_attribution_backend/infrastructure/acceleration/NativeBindings$OpenCl";
#endif
    const std::string graphType = "L" + std::string(package) + "Graph;", configType = "L" + std::string(package) + "Config;", resultType = "L" + std::string(package) + "Result;";
    std::string createSignature = "(Ljava/lang/String;Ljava/lang/String;" + graphType + configType + ")J";
    std::string compareSignature = "(J" + graphType + "[[D)" + resultType;
    JNINativeMethod methods[] = {
        {const_cast<char*>("devices"), const_cast<char*>("()[[Ljava/lang/String;"), reinterpret_cast<void*>(devices)},
        {const_cast<char*>("frontier"), const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;[I[I[I[I[J[BJ)[I"), reinterpret_cast<void*>(frontier)},
        {const_cast<char*>("create"), createSignature.data(), reinterpret_cast<void*>(create)},
        {const_cast<char*>("compare"), compareSignature.data(), reinterpret_cast<void*>(compare)},
        {const_cast<char*>("close"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(close)}
    };
    auto c = e->FindClass(holder); if (!c || e->RegisterNatives(c, methods, 5) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_8;
}
