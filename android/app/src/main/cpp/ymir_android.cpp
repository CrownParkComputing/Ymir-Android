#include <jni.h>

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <ymir/ymir.hpp>

#include <algorithm>
#include <atomic>
#include <array>
#include <cstring>
#include <chrono>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char *kLogTag = "YmirAndroid";
constexpr size_t kSmpcPersistentStateSize = 25;

enum class RendererBackend {
    Software = 0,
    OpenGLES = 1,
    Vulkan = 2,
};

enum class AspectMode {
    Standard4_3 = 0,
    Wide16_9 = 1,
};

enum class PadButton {
    Up = 0,
    Down,
    Left,
    Right,
    Start,
    A,
    B,
    C,
    X,
    Y,
    Z,
    L,
    R,
};

struct DisplayViewport {
    int x = 0;
    int y = 0;
    int width = 1;
    int height = 1;
};

struct SourceCrop {
    uint32 x = 0;
    uint32 y = 0;
    uint32 width = 1;
    uint32 height = 1;
};

void EnsureSmpcPersistentStateInitialized(const std::string &path);

class AndroidAudioOutput {
public:
    AndroidAudioOutput()
        : m_buffer(kCapacityFrames * kChannels) {}

    ~AndroidAudioOutput() {
        Stop();
    }

    bool Start() {
        std::lock_guard lock(m_streamMutex);
        if (m_stream != nullptr) {
            return true;
        }

        AAudioStreamBuilder *builder = nullptr;
        aaudio_result_t result = AAudio_createStreamBuilder(&builder);
        if (result != AAUDIO_OK || builder == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "AAudio builder failed: %s",
                                AAudio_convertResultToText(result));
            return false;
        }

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSampleRate(builder, kSampleRate);
        AAudioStreamBuilder_setChannelCount(builder, kChannels);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setDataCallback(builder, &AndroidAudioOutput::DataCallback, this);

        result = AAudioStreamBuilder_openStream(builder, &m_stream);
        AAudioStreamBuilder_delete(builder);
        if (result != AAUDIO_OK || m_stream == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "AAudio stream open failed: %s",
                                AAudio_convertResultToText(result));
            m_stream = nullptr;
            return false;
        }

        result = AAudioStream_requestStart(m_stream);
        if (result != AAUDIO_OK) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "AAudio stream start failed: %s",
                                AAudio_convertResultToText(result));
            AAudioStream_close(m_stream);
            m_stream = nullptr;
            return false;
        }
        return true;
    }

    void Stop() {
        AAudioStream *stream = nullptr;
        {
            std::lock_guard lock(m_streamMutex);
            stream = m_stream;
            m_stream = nullptr;
        }
        if (stream != nullptr) {
            AAudioStream_requestStop(stream);
            AAudioStream_close(stream);
        }
        Clear();
    }

    void Push(sint16 left, sint16 right) {
        if (m_muted.load(std::memory_order_relaxed)) {
            return;
        }
        std::lock_guard lock(m_bufferMutex);
        if (m_queuedFrames == kCapacityFrames) {
            m_readFrame = (m_readFrame + 1) % kCapacityFrames;
            --m_queuedFrames;
            ++m_droppedFrames;
        }

        const size_t offset = m_writeFrame * kChannels;
        m_buffer[offset] = left;
        m_buffer[offset + 1] = right;
        m_writeFrame = (m_writeFrame + 1) % kCapacityFrames;
        ++m_queuedFrames;
    }

    bool IsStarted() const {
        std::lock_guard lock(m_streamMutex);
        return m_stream != nullptr;
    }

    uint64_t Underruns() const {
        return m_underruns.load(std::memory_order_relaxed);
    }

    uint64_t DroppedFrames() const {
        return m_droppedFrames.load(std::memory_order_relaxed);
    }

    void SetMuted(bool muted) {
        m_muted.store(muted, std::memory_order_relaxed);
        if (muted) {
            Clear();
        }
    }

    bool IsMuted() const {
        return m_muted.load(std::memory_order_relaxed);
    }

private:
    static constexpr int32_t kSampleRate = 44100;
    static constexpr int32_t kChannels = 2;
    static constexpr size_t kCapacityFrames = 44100 / 2;

    static aaudio_data_callback_result_t DataCallback(AAudioStream *, void *userData, void *audioData,
                                                      int32_t numFrames) {
        return static_cast<AndroidAudioOutput *>(userData)->OnAudioData(audioData, numFrames);
    }

    aaudio_data_callback_result_t OnAudioData(void *audioData, int32_t numFrames) {
        auto *out = static_cast<int16_t *>(audioData);
        if (m_muted.load(std::memory_order_relaxed)) {
            std::memset(out, 0, static_cast<size_t>(numFrames) * kChannels * sizeof(int16_t));
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
        int32_t framesWritten = 0;

        {
            std::lock_guard lock(m_bufferMutex);
            const int32_t available = static_cast<int32_t>(std::min<size_t>(m_queuedFrames, numFrames));
            for (; framesWritten < available; ++framesWritten) {
                const size_t sourceOffset = m_readFrame * kChannels;
                const size_t outputOffset = static_cast<size_t>(framesWritten) * kChannels;
                out[outputOffset] = m_buffer[sourceOffset];
                out[outputOffset + 1] = m_buffer[sourceOffset + 1];
                m_readFrame = (m_readFrame + 1) % kCapacityFrames;
            }
            m_queuedFrames -= static_cast<size_t>(framesWritten);
        }

        if (framesWritten < numFrames) {
            std::memset(out + static_cast<size_t>(framesWritten) * kChannels, 0,
                        static_cast<size_t>(numFrames - framesWritten) * kChannels * sizeof(int16_t));
            ++m_underruns;
        }

        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    void Clear() {
        std::lock_guard lock(m_bufferMutex);
        m_readFrame = 0;
        m_writeFrame = 0;
        m_queuedFrames = 0;
    }

    mutable std::mutex m_streamMutex;
    mutable std::mutex m_bufferMutex;
    AAudioStream *m_stream = nullptr;
    std::vector<int16_t> m_buffer;
    size_t m_readFrame = 0;
    size_t m_writeFrame = 0;
    size_t m_queuedFrames = 0;
    std::atomic_uint64_t m_underruns = 0;
    std::atomic_uint64_t m_droppedFrames = 0;
    std::atomic_bool m_muted = false;
};

class AndroidYmirRuntime {
public:
    AndroidYmirRuntime() {
        m_saturn.SMPC.GetPeripheralPort1().ConnectControlPad();
        m_saturn.SMPC.GetPeripheralPort1().SetPeripheralReportCallback({
            this,
            [](ymir::peripheral::PeripheralReport &report, void *ctx) {
                static_cast<AndroidYmirRuntime *>(ctx)->ReadPeripheral(report);
            },
        });
        m_saturn.VDP.UseSoftwareRenderer();
        m_saturn.VDP.SetSoftwareRenderCallback({
            this,
            [](uint32 *fb, uint32 width, uint32 height, void *ctx) {
                static_cast<AndroidYmirRuntime *>(ctx)->PresentSoftwareFrame(fb, width, height);
            },
        });
        m_saturn.SCSP.SetSampleCallback({
            this,
            [](sint16 left, sint16 right, void *ctx) {
                static_cast<AndroidYmirRuntime *>(ctx)->ReceiveAudioSample(left, right);
            },
        });
        m_audio.Start();
    }

    ~AndroidYmirRuntime() {
        Stop();
        m_audio.Stop();
        ClearSurface();
    }

    void SetSurface(ANativeWindow *window) {
        std::lock_guard lock(m_windowMutex);
        DestroyGLESLocked();
        DestroyVulkanLocked();
        if (m_window != nullptr) {
            ANativeWindow_release(m_window);
        }
        m_window = window;
        m_windowWidth = 0;
        m_windowHeight = 0;
        m_glesUnavailable = false;
        m_vulkanUnavailable = false;
    }

    void SetSurfaceSize(int width, int height) {
        std::lock_guard lock(m_windowMutex);
        m_surfaceWidth = static_cast<uint32>(std::max(1, width));
        m_surfaceHeight = static_cast<uint32>(std::max(1, height));
        m_windowWidth = 0;
        m_windowHeight = 0;
    }

    void ClearSurface() {
        std::lock_guard lock(m_windowMutex);
        DestroyGLESLocked();
        DestroyVulkanLocked();
        if (m_window != nullptr) {
            ANativeWindow_release(m_window);
            m_window = nullptr;
        }
        m_windowWidth = 0;
        m_windowHeight = 0;
        m_surfaceWidth = 0;
        m_surfaceHeight = 0;
    }

    void SetRendererBackend(RendererBackend backend) {
        {
            std::lock_guard lock(m_stateMutex);
            m_requestedBackend.store(static_cast<int>(backend), std::memory_order_relaxed);
            m_backend.store(static_cast<int>(backend), std::memory_order_relaxed);
        }
        {
            std::unique_lock windowLock(m_windowMutex, std::try_to_lock);
            if (windowLock.owns_lock()) {
                m_glesUnavailable = false;
                m_vulkanUnavailable = false;
                if (backend != RendererBackend::OpenGLES) {
                    DestroyGLESLocked();
                }
                if (backend != RendererBackend::Vulkan) {
                    DestroyVulkanLocked();
                }
                m_windowWidth = 0;
                m_windowHeight = 0;
            }
        }
        if (backend == RendererBackend::Software) {
            return;
        } else if (backend == RendererBackend::OpenGLES) {
            return;
        } else if (backend == RendererBackend::Vulkan) {
            __android_log_print(ANDROID_LOG_INFO, kLogTag, "Vulkan presenter requested");
        }
    }

    void SetDisplayOptions(AspectMode aspectMode, bool bezelEnabled, bool crtEnabled, bool cropEnabled) {
        m_aspectMode.store(static_cast<int>(aspectMode), std::memory_order_relaxed);
        m_bezelEnabled.store(bezelEnabled, std::memory_order_relaxed);
        m_crtEnabled.store(crtEnabled, std::memory_order_relaxed);
        m_cropEnabled.store(cropEnabled, std::memory_order_relaxed);
    }

    void SetPresentationPaused(bool paused) {
        m_presentationPaused.store(paused, std::memory_order_relaxed);
    }

    void SetAudioMuted(bool muted) {
        m_audio.SetMuted(muted);
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "Audio muted: %s", muted ? "true" : "false");
    }

    void SetCustomVulkanDriverPath(std::string path) {
        std::lock_guard lock(m_stateMutex);
        m_customVulkanDriverPath = std::move(path);
    }

    std::string LoadBiosFile(const std::string &path) {
        SaveInternalBackupMemory();
        SaveSmpcPersistentState();
        Stop();
        std::array<uint8, ymir::sys::kIPLSize> bios{};
        std::ifstream in{path, std::ios::binary | std::ios::ate};
        if (!in) {
            return "BIOS load failed: could not open file";
        }

        const std::streamsize size = in.tellg();
        if (size != static_cast<std::streamsize>(bios.size())) {
            std::ostringstream out;
            out << "BIOS load failed: expected " << bios.size() << " bytes, got " << size;
            return out.str();
        }

        in.seekg(0, std::ios::beg);
        in.read(reinterpret_cast<char *>(bios.data()), static_cast<std::streamsize>(bios.size()));
        if (!in) {
            return "BIOS load failed: could not read complete file";
        }

        {
            std::lock_guard lock(m_emuMutex);
            m_saturn.LoadIPL(bios);
            m_saturn.Reset(true);
            m_frames.store(0);
        }
        {
            std::lock_guard lock(m_stateMutex);
            m_biosPath = path;
            m_biosLoaded = true;
        }
        return "BIOS loaded";
    }

    std::string LoadInternalBackupMemory(const std::string &path) {
        std::error_code error{};
        {
            std::lock_guard lock(m_emuMutex);
            m_saturn.LoadInternalBackupMemoryImage(path, false, error);
        }
        if (error) {
            return "Backup memory failed: " + error.message();
        }
        {
            std::lock_guard lock(m_stateMutex);
            m_internalBackupPath = path;
        }
        return "Backup memory ready";
    }

    std::string SaveInternalBackupMemory() {
        std::string path{};
        {
            std::lock_guard lock(m_stateMutex);
            path = m_internalBackupPath;
        }
        if (path.empty()) {
            return "Backup memory save skipped: no path";
        }

        std::vector<uint8> data{};
        {
            std::lock_guard lock(m_emuMutex);
            data = m_saturn.mem.GetInternalBackupRAM().ReadAll();
        }
        if (data.empty()) {
            return "Backup memory save skipped: empty data";
        }

        std::fstream out{path, std::ios::binary | std::ios::in | std::ios::out};
        if (!out) {
            return "Backup memory save failed: could not open file";
        }
        out.seekp(0, std::ios::beg);
        out.write(reinterpret_cast<const char *>(data.data()), static_cast<std::streamsize>(data.size()));
        out.flush();
        if (!out) {
            return "Backup memory save failed: could not write file";
        }
        return "Backup memory saved";
    }

    std::string LoadSmpcPersistentState(const std::string &path) {
        EnsureSmpcPersistentStateInitialized(path);
        std::error_code error{};
        {
            std::lock_guard lock(m_emuMutex);
            m_saturn.SMPC.LoadPersistentDataFrom(path, error);
        }
        if (error) {
            return "SMPC state failed: " + error.message();
        }
        return "SMPC state ready";
    }

    std::string SaveSmpcPersistentState() {
        std::filesystem::path path{};
        {
            std::lock_guard lock(m_emuMutex);
            path = m_saturn.SMPC.GetPersistentDataPath();
        }
        if (path.empty()) {
            return "SMPC state save skipped: no path";
        }

        std::error_code error{};
        {
            std::lock_guard lock(m_emuMutex);
            m_saturn.SMPC.SavePersistentDataTo(path, error);
        }
        if (error) {
            return "SMPC state save failed: " + error.message();
        }
        return "SMPC state saved";
    }

    std::string LoadGameFile(const std::string &path) {
        SaveInternalBackupMemory();
        SaveSmpcPersistentState();
        Stop();

        ymir::media::Disc disc{};
        std::string message = "unknown loader error";
        const bool ok = ymir::media::LoadDisc(path, disc, false,
                                              [&](ymir::media::MessageType, std::string text) {
                                                  if (!text.empty()) {
                                                      message = std::move(text);
                                                  }
                                              });
        if (!ok) {
            return "Game load failed: " + message;
        }

        {
            std::lock_guard lock(m_emuMutex);
            m_saturn.LoadDisc(std::move(disc));
            m_saturn.Reset(true);
            m_frames.store(0);
            m_lastFpsFrame = 0;
            m_lastFpsTime = std::chrono::steady_clock::now();
            m_fps.store(0.0);
        }
        {
            std::lock_guard lock(m_stateMutex);
            m_gamePath = path;
        }
        return "Game loaded";
    }

    void SetGamesFolderUri(std::string uri) {
        std::lock_guard lock(m_stateMutex);
        m_gamesFolderUri = std::move(uri);
    }

    void SetPadButton(PadButton button, bool pressed) {
        const auto bit = ToYmirButton(button);
        std::lock_guard lock(m_inputMutex);
        if (pressed) {
            m_padButtons &= ~bit;
        } else {
            m_padButtons |= bit;
        }
    }

    void Reset() {
        std::lock_guard lock(m_emuMutex);
        m_saturn.Reset(true);
        m_frames.store(0);
        m_fps.store(0.0);
    }

    bool RunFrame() {
        std::lock_guard lock(m_emuMutex);
        m_saturn.RunFrame();
        const uint64_t frames = m_frames.fetch_add(1, std::memory_order_relaxed) + 1;
        const auto now = std::chrono::steady_clock::now();
        const auto elapsed = std::chrono::duration<double>(now - m_lastFpsTime).count();
        if (elapsed >= 0.5) {
            m_fps.store(static_cast<double>(frames - m_lastFpsFrame) / elapsed, std::memory_order_relaxed);
            m_lastFpsFrame = frames;
            m_lastFpsTime = now;
        }
        return true;
    }

    void Start() {
        bool expected = false;
        if (!m_running.compare_exchange_strong(expected, true)) {
            return;
        }
        m_thread = std::thread([this] {
            while (m_running.load(std::memory_order_relaxed)) {
                RunFrame();
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
        });
    }

    void Stop() {
        SaveInternalBackupMemory();
        SaveSmpcPersistentState();
        m_running.store(false);
        if (m_thread.joinable()) {
            m_thread.join();
        }
    }

    std::string Status() const {
        std::lock_guard stateLock(m_stateMutex);
        std::unique_lock windowLock(m_windowMutex, std::try_to_lock);
        const bool windowStatusAvailable = windowLock.owns_lock();
        std::ostringstream out;
        const auto activeBackend = static_cast<RendererBackend>(m_backend.load(std::memory_order_relaxed));
        const auto requestedBackend = static_cast<RendererBackend>(m_requestedBackend.load(std::memory_order_relaxed));
        out << "activeBackend=" << BackendName(activeBackend)
            << "\nrequestedBackend=" << BackendName(requestedBackend);
        if (requestedBackend == RendererBackend::Vulkan && activeBackend == RendererBackend::Software) {
            out << "\nrendererNote=vulkan presenter unavailable; software fallback active";
        }
        out
            << "\nvdpRenderer=software"
            << "\naspect="
            << (m_aspectMode.load(std::memory_order_relaxed) == static_cast<int>(AspectMode::Wide16_9) ? "16:9" : "4:3")
            << "\nbezel=" << (m_bezelEnabled.load(std::memory_order_relaxed) ? "true" : "false")
            << "\ncrt=" << (m_crtEnabled.load(std::memory_order_relaxed) ? "true" : "false")
            << "\ncrop=" << (m_cropEnabled.load(std::memory_order_relaxed) ? "true" : "false")
            << "\naudio=" << (m_audio.IsStarted() ? "started" : "stopped")
            << "\naudioMuted=" << (m_audio.IsMuted() ? "true" : "false")
            << "\naudioUnderruns=" << m_audio.Underruns()
            << "\naudioDroppedFrames=" << m_audio.DroppedFrames()
            << "\nrunning=" << (m_running.load() ? "true" : "false")
            << "\nframes=" << m_frames.load(std::memory_order_relaxed)
            << "\nsurface="
            << (windowStatusAvailable ? (m_window != nullptr ? "attached" : "none") : "busy")
            << "\nbios=" << (m_biosLoaded ? m_biosPath : "not loaded")
            << "\ninternalBackup=" << (m_internalBackupPath.empty() ? "not loaded" : m_internalBackupPath)
            << "\ngamesFolder=" << (m_gamesFolderUri.empty() ? "not selected" : m_gamesFolderUri)
            << "\ngame=" << (m_gamePath.empty() ? "not selected" : m_gamePath)
            << "\nfps=" << m_fps.load(std::memory_order_relaxed)
            << "\ncustomVulkanDriver="
            << (m_customVulkanDriverPath.empty() ? "none" : m_customVulkanDriverPath);
        return out.str();
    }

    double GetFps() const {
        return m_fps.load(std::memory_order_relaxed);
    }

private:
    void ReadPeripheral(ymir::peripheral::PeripheralReport &report) {
        if (report.type != ymir::peripheral::PeripheralType::ControlPad) {
            return;
        }
        std::lock_guard lock(m_inputMutex);
        report.report.controlPad.buttons = m_padButtons;
    }

    void PresentSoftwareFrame(const uint32 *fb, uint32 width, uint32 height) {
        if (m_presentationPaused.load(std::memory_order_relaxed)) {
            return;
        }
        std::unique_lock lock(m_windowMutex, std::try_to_lock);
        if (!lock.owns_lock()) {
            return;
        }
        if (m_window == nullptr || fb == nullptr || width == 0 || height == 0) {
            return;
        }

        const auto backend = static_cast<RendererBackend>(m_backend.load(std::memory_order_relaxed));
        if (backend == RendererBackend::OpenGLES) {
            PresentGLESFrameLocked(fb, width, height);
            return;
        }
        if (backend == RendererBackend::Vulkan) {
            PresentVulkanFrameLocked(fb, width, height);
            return;
        }
        if (m_eglDisplay != EGL_NO_DISPLAY) {
            DestroyGLESLocked();
        }

        const uint32 targetWidth = m_surfaceWidth != 0 ? m_surfaceWidth : static_cast<uint32>(std::max(1, ANativeWindow_getWidth(m_window)));
        const uint32 targetHeight = m_surfaceHeight != 0 ? m_surfaceHeight : static_cast<uint32>(std::max(1, ANativeWindow_getHeight(m_window)));
        if (m_windowWidth != targetWidth || m_windowHeight != targetHeight) {
            ANativeWindow_setBuffersGeometry(m_window, static_cast<int32_t>(targetWidth), static_cast<int32_t>(targetHeight),
                                             WINDOW_FORMAT_RGBA_8888);
            m_windowWidth = targetWidth;
            m_windowHeight = targetHeight;
        }

        ANativeWindow_Buffer buffer{};
        if (ANativeWindow_lock(m_window, &buffer, nullptr) != 0) {
            return;
        }

        BlitSoftwareFrameToBufferLocked(fb, width, height, buffer);

        ANativeWindow_unlockAndPost(m_window);
    }

    void ReceiveAudioSample(sint16 left, sint16 right) {
        m_audio.Push(left, right);
    }

    bool EnsureVulkanLocked(uint32 width, uint32 height) {
        if (m_vkDevice != VK_NULL_HANDLE
                && m_vkSwapchain != VK_NULL_HANDLE
                && m_vkSourceWidth == width
                && m_vkSourceHeight == height
                && m_windowWidth == (m_surfaceWidth != 0 ? m_surfaceWidth : static_cast<uint32>(std::max(1, ANativeWindow_getWidth(m_window))))
                && m_windowHeight == (m_surfaceHeight != 0 ? m_surfaceHeight : static_cast<uint32>(std::max(1, ANativeWindow_getHeight(m_window)))) ) {
            return true;
        }
        DestroyVulkanLocked();
        if (m_vulkanUnavailable || m_window == nullptr || width == 0 || height == 0) {
            return false;
        }

        const char *instanceExtensions[] = {"VK_KHR_surface", "VK_KHR_android_surface"};
        VkApplicationInfo appInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        appInfo.pApplicationName = "YmirAndroid";
        appInfo.apiVersion = VK_API_VERSION_1_0;
        VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        instanceInfo.pApplicationInfo = &appInfo;
        instanceInfo.enabledExtensionCount = 2;
        instanceInfo.ppEnabledExtensionNames = instanceExtensions;
        if (vkCreateInstance(&instanceInfo, nullptr, &m_vkInstance) != VK_SUCCESS) {
            MarkVulkanUnavailableLocked("vkCreateInstance failed");
            return false;
        }

        VkAndroidSurfaceCreateInfoKHR surfaceInfo{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        surfaceInfo.window = m_window;
        if (vkCreateAndroidSurfaceKHR(m_vkInstance, &surfaceInfo, nullptr, &m_vkSurface) != VK_SUCCESS) {
            MarkVulkanUnavailableLocked("vkCreateAndroidSurfaceKHR failed");
            return false;
        }

        uint32_t physicalDeviceCount = 0;
        vkEnumeratePhysicalDevices(m_vkInstance, &physicalDeviceCount, nullptr);
        if (physicalDeviceCount == 0) {
            MarkVulkanUnavailableLocked("no Vulkan physical devices");
            return false;
        }
        std::vector<VkPhysicalDevice> physicalDevices(physicalDeviceCount);
        vkEnumeratePhysicalDevices(m_vkInstance, &physicalDeviceCount, physicalDevices.data());
        for (VkPhysicalDevice device : physicalDevices) {
            uint32_t queueFamilyCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(device, &queueFamilyCount, nullptr);
            std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
            vkGetPhysicalDeviceQueueFamilyProperties(device, &queueFamilyCount, queueFamilies.data());
            for (uint32_t index = 0; index < queueFamilyCount; ++index) {
                VkBool32 presentSupported = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(device, index, m_vkSurface, &presentSupported);
                if ((queueFamilies[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0 && presentSupported == VK_TRUE) {
                    m_vkPhysicalDevice = device;
                    m_vkQueueFamily = index;
                    break;
                }
            }
            if (m_vkPhysicalDevice != VK_NULL_HANDLE) {
                break;
            }
        }
        if (m_vkPhysicalDevice == VK_NULL_HANDLE) {
            MarkVulkanUnavailableLocked("no graphics+present Vulkan queue");
            return false;
        }

        float queuePriority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queueInfo.queueFamilyIndex = m_vkQueueFamily;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &queuePriority;
        const char *deviceExtensions[] = {"VK_KHR_swapchain"};
        VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = 1;
        deviceInfo.ppEnabledExtensionNames = deviceExtensions;
        if (vkCreateDevice(m_vkPhysicalDevice, &deviceInfo, nullptr, &m_vkDevice) != VK_SUCCESS) {
            MarkVulkanUnavailableLocked("vkCreateDevice failed");
            return false;
        }
        vkGetDeviceQueue(m_vkDevice, m_vkQueueFamily, 0, &m_vkQueue);

        VkSurfaceCapabilitiesKHR caps{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(m_vkPhysicalDevice, m_vkSurface, &caps);
        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(m_vkPhysicalDevice, m_vkSurface, &formatCount, nullptr);
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(m_vkPhysicalDevice, m_vkSurface, &formatCount, formats.data());
        VkSurfaceFormatKHR chosenFormat = formats.empty()
                ? VkSurfaceFormatKHR{VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR}
                : formats[0];
        for (const auto &format : formats) {
            if (format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM) {
                chosenFormat = format;
                break;
            }
        }
        m_vkSwapchainFormat = chosenFormat.format;

        const uint32 targetWidth = m_surfaceWidth != 0 ? m_surfaceWidth : static_cast<uint32>(std::max(1, ANativeWindow_getWidth(m_window)));
        const uint32 targetHeight = m_surfaceHeight != 0 ? m_surfaceHeight : static_cast<uint32>(std::max(1, ANativeWindow_getHeight(m_window)));
        VkExtent2D extent{};
        if (caps.currentExtent.width != UINT32_MAX) {
            extent = caps.currentExtent;
        } else {
            extent.width = std::max(caps.minImageExtent.width, std::min(caps.maxImageExtent.width, targetWidth));
            extent.height = std::max(caps.minImageExtent.height, std::min(caps.maxImageExtent.height, targetHeight));
        }
        m_windowWidth = extent.width;
        m_windowHeight = extent.height;

        uint32_t imageCount = std::max(caps.minImageCount + 1, 2u);
        if (caps.maxImageCount > 0) {
            imageCount = std::min(imageCount, caps.maxImageCount);
        }
        VkSwapchainCreateInfoKHR swapchainInfo{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        swapchainInfo.surface = m_vkSurface;
        swapchainInfo.minImageCount = imageCount;
        swapchainInfo.imageFormat = chosenFormat.format;
        swapchainInfo.imageColorSpace = chosenFormat.colorSpace;
        swapchainInfo.imageExtent = extent;
        swapchainInfo.imageArrayLayers = 1;
        swapchainInfo.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        swapchainInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        swapchainInfo.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0
                ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                : caps.currentTransform;
        swapchainInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        swapchainInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        swapchainInfo.clipped = VK_TRUE;
        if (vkCreateSwapchainKHR(m_vkDevice, &swapchainInfo, nullptr, &m_vkSwapchain) != VK_SUCCESS) {
            MarkVulkanUnavailableLocked("vkCreateSwapchainKHR failed");
            return false;
        }
        uint32_t swapchainImageCount = 0;
        vkGetSwapchainImagesKHR(m_vkDevice, m_vkSwapchain, &swapchainImageCount, nullptr);
        m_vkSwapchainImages.resize(swapchainImageCount);
        vkGetSwapchainImagesKHR(m_vkDevice, m_vkSwapchain, &swapchainImageCount, m_vkSwapchainImages.data());

        VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = m_vkQueueFamily;
        if (vkCreateCommandPool(m_vkDevice, &poolInfo, nullptr, &m_vkCommandPool) != VK_SUCCESS) {
            MarkVulkanUnavailableLocked("vkCreateCommandPool failed");
            return false;
        }
        VkCommandBufferAllocateInfo cmdInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cmdInfo.commandPool = m_vkCommandPool;
        cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cmdInfo.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(m_vkDevice, &cmdInfo, &m_vkCommandBuffer) != VK_SUCCESS) {
            MarkVulkanUnavailableLocked("vkAllocateCommandBuffers failed");
            return false;
        }

        VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        vkCreateSemaphore(m_vkDevice, &semaphoreInfo, nullptr, &m_vkImageAvailable);
        vkCreateSemaphore(m_vkDevice, &semaphoreInfo, nullptr, &m_vkRenderFinished);
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(m_vkDevice, &fenceInfo, nullptr, &m_vkFrameFence);

        m_vkSourceWidth = width;
        m_vkSourceHeight = height;
        const VkDeviceSize sourceBytes = static_cast<VkDeviceSize>(width) * height * sizeof(uint32);
        if (!CreateVulkanBufferLocked(sourceBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                      m_vkStagingBuffer, m_vkStagingMemory)) {
            MarkVulkanUnavailableLocked("staging buffer allocation failed");
            return false;
        }
        if (!CreateVulkanImageLocked(width, height, VK_FORMAT_R8G8B8A8_UNORM,
                                     VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                                     m_vkSourceImage, m_vkSourceMemory)) {
            MarkVulkanUnavailableLocked("source image allocation failed");
            return false;
        }
        m_vkSourceLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "Vulkan presenter ready: %ux%u -> %ux%u",
                            width, height, m_windowWidth, m_windowHeight);
        return true;
    }

    void PresentVulkanFrameLocked(const uint32 *fb, uint32 width, uint32 height) {
        if (!EnsureVulkanLocked(width, height)) {
            m_backend.store(static_cast<int>(RendererBackend::Software), std::memory_order_relaxed);
            PresentSoftwareFrameFallbackLocked(fb, width, height);
            return;
        }

        const uint32 *uploadFrame = fb;
        if (m_crtEnabled.load(std::memory_order_relaxed)) {
            uploadFrame = ApplyVulkanCrtFilterLocked(fb, width, height);
        }

        const VkDeviceSize sourceBytes = static_cast<VkDeviceSize>(width) * height * sizeof(uint32);
        void *mapped = nullptr;
        if (vkMapMemory(m_vkDevice, m_vkStagingMemory, 0, sourceBytes, 0, &mapped) != VK_SUCCESS || mapped == nullptr) {
            PresentSoftwareFrameFallbackLocked(fb, width, height);
            return;
        }
        std::memcpy(mapped, uploadFrame, static_cast<size_t>(sourceBytes));
        vkUnmapMemory(m_vkDevice, m_vkStagingMemory);

        vkWaitForFences(m_vkDevice, 1, &m_vkFrameFence, VK_TRUE, UINT64_MAX);
        vkResetFences(m_vkDevice, 1, &m_vkFrameFence);

        uint32_t imageIndex = 0;
        VkResult acquireResult = vkAcquireNextImageKHR(m_vkDevice, m_vkSwapchain, UINT64_MAX, m_vkImageAvailable,
                                                       VK_NULL_HANDLE, &imageIndex);
        if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR || acquireResult == VK_SUBOPTIMAL_KHR) {
            DestroyVulkanLocked();
            return;
        }
        if (acquireResult != VK_SUCCESS) {
            PresentSoftwareFrameFallbackLocked(fb, width, height);
            return;
        }

        vkResetCommandBuffer(m_vkCommandBuffer, 0);
        VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        vkBeginCommandBuffer(m_vkCommandBuffer, &beginInfo);

        TransitionImageLocked(m_vkSourceImage, m_vkSourceLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                              VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        VkBufferImageCopy copyRegion{};
        copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.imageSubresource.layerCount = 1;
        copyRegion.imageExtent = {width, height, 1};
        vkCmdCopyBufferToImage(m_vkCommandBuffer, m_vkStagingBuffer, m_vkSourceImage,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);
        TransitionImageLocked(m_vkSourceImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                              VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                              VK_ACCESS_TRANSFER_READ_BIT);
        m_vkSourceLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

        VkImage swapImage = m_vkSwapchainImages[imageIndex];
        TransitionImageLocked(swapImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                              0, VK_ACCESS_TRANSFER_WRITE_BIT);
        VkClearColorValue clearColor{};
        const bool bezelEnabled = m_bezelEnabled.load(std::memory_order_relaxed);
        clearColor.float32[0] = bezelEnabled ? 0.015f : 0.0f;
        clearColor.float32[1] = bezelEnabled ? 0.018f : 0.0f;
        clearColor.float32[2] = bezelEnabled ? 0.022f : 0.0f;
        clearColor.float32[3] = 1.0f;
        VkImageSubresourceRange clearRange{};
        clearRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        clearRange.levelCount = 1;
        clearRange.layerCount = 1;
        vkCmdClearColorImage(m_vkCommandBuffer, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clearColor, 1,
                             &clearRange);

        const DisplayViewport viewport = CalculateViewportLocked(static_cast<int>(m_windowWidth),
                                                                 static_cast<int>(m_windowHeight));
        const SourceCrop crop = CalculateSourceCropLocked(width, height);
        VkImageBlit blit{};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[0] = {static_cast<int32_t>(crop.x), static_cast<int32_t>(crop.y), 0};
        blit.srcOffsets[1] = {static_cast<int32_t>(crop.x + crop.width), static_cast<int32_t>(crop.y + crop.height), 1};
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[0] = {viewport.x, viewport.y, 0};
        blit.dstOffsets[1] = {viewport.x + viewport.width, viewport.y + viewport.height, 1};
        vkCmdBlitImage(m_vkCommandBuffer, m_vkSourceImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
        TransitionImageLocked(swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                              VK_ACCESS_TRANSFER_WRITE_BIT, 0);
        vkEndCommandBuffer(m_vkCommandBuffer);

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submitInfo.waitSemaphoreCount = 1;
        submitInfo.pWaitSemaphores = &m_vkImageAvailable;
        submitInfo.pWaitDstStageMask = &waitStage;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &m_vkCommandBuffer;
        submitInfo.signalSemaphoreCount = 1;
        submitInfo.pSignalSemaphores = &m_vkRenderFinished;
        if (vkQueueSubmit(m_vkQueue, 1, &submitInfo, m_vkFrameFence) != VK_SUCCESS) {
            PresentSoftwareFrameFallbackLocked(fb, width, height);
            return;
        }

        VkPresentInfoKHR presentInfo{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &m_vkRenderFinished;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &m_vkSwapchain;
        presentInfo.pImageIndices = &imageIndex;
        VkResult presentResult = vkQueuePresentKHR(m_vkQueue, &presentInfo);
        if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
            DestroyVulkanLocked();
        }
    }

    bool EnsureGLESLocked(uint32 width, uint32 height) {
        if (m_eglDisplay != EGL_NO_DISPLAY && m_eglSurface != EGL_NO_SURFACE && m_glProgram != 0) {
            return true;
        }
        if (m_glesUnavailable) {
            return false;
        }
        if (m_window == nullptr) {
            return false;
        }
        ANativeWindow_setBuffersGeometry(m_window, 0, 0, WINDOW_FORMAT_RGBA_8888);

        m_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (m_eglDisplay == EGL_NO_DISPLAY || eglInitialize(m_eglDisplay, nullptr, nullptr) == EGL_FALSE) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "eglInitialize failed");
            m_glesUnavailable = true;
            DestroyGLESLocked();
            return false;
        }

        const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE,
        };
        EGLConfig config = nullptr;
        EGLint configCount = 0;
        if (eglChooseConfig(m_eglDisplay, configAttribs, &config, 1, &configCount) == EGL_FALSE || configCount == 0) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "eglChooseConfig failed");
            m_glesUnavailable = true;
            DestroyGLESLocked();
            return false;
        }

        m_eglSurface = eglCreateWindowSurface(m_eglDisplay, config, m_window, nullptr);
        if (m_eglSurface == EGL_NO_SURFACE) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "eglCreateWindowSurface failed");
            m_glesUnavailable = true;
            DestroyGLESLocked();
            return false;
        }

        const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        m_eglContext = eglCreateContext(m_eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
        if (m_eglContext == EGL_NO_CONTEXT ||
            eglMakeCurrent(m_eglDisplay, m_eglSurface, m_eglSurface, m_eglContext) == EGL_FALSE) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "eglCreateContext/eglMakeCurrent failed");
            m_glesUnavailable = true;
            DestroyGLESLocked();
            return false;
        }

        const char *vertexShader = R"(#version 300 es
precision mediump float;
out vec2 vTexCoord;
void main() {
    vec2 positions[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
    vec2 texCoords[3] = vec2[3](vec2(0.0, 1.0), vec2(2.0, 1.0), vec2(0.0, -1.0));
    gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
    vTexCoord = texCoords[gl_VertexID];
})";
        const char *fragmentShader = R"(#version 300 es
precision mediump float;
uniform sampler2D uFrame;
uniform vec2 uTexelSize;
uniform vec4 uCropRect;
uniform int uCrtEnabled;
in vec2 vTexCoord;
out vec4 fragColor;

vec2 curveUv(vec2 uv) {
    vec2 centered = uv * 2.0 - 1.0;
    centered += centered * (centered.yx * centered.yx) * vec2(0.06, 0.08);
    return centered * 0.5 + 0.5;
}

vec3 applyCrt(vec2 curvedUv, vec3 color) {
    float scanPos = curvedUv.y / max(uTexelSize.y, 0.0001);
    float scanline = 0.5 + 0.5 * sin(scanPos * 6.2831853);
    scanline = mix(1.0, scanline, 0.34);

    float maskPos = fract((curvedUv.x / max(uTexelSize.x, 0.0001)) * 3.0);
    vec3 mask = vec3(1.0);
    if (maskPos < 0.333) {
        mask = vec3(1.0, 0.62, 0.62);
    } else if (maskPos < 0.666) {
        mask = vec3(0.62, 1.0, 0.62);
    } else {
        mask = vec3(0.62, 0.62, 1.0);
    }
    mask = mix(vec3(1.0), mask, 0.26);

    vec2 dist = abs(curvedUv - 0.5);
    float vignette = clamp(1.0 - dot(dist, dist) * 1.5, 0.0, 1.0);
    return color * scanline * mask * vignette;
}

void main() {
    vec2 uv = vTexCoord;
    if (uCrtEnabled != 0) {
        uv = curveUv(uv);
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
    }
    vec2 sampleUv = uCropRect.xy + uv * uCropRect.zw;
    vec4 color = texture(uFrame, sampleUv);
    if (uCrtEnabled != 0) {
        color.rgb = applyCrt(uv, color.rgb);
    }
    fragColor = color;
})";

        const GLuint vs = CompileShaderLocked(GL_VERTEX_SHADER, vertexShader);
        const GLuint fs = CompileShaderLocked(GL_FRAGMENT_SHADER, fragmentShader);
        if (vs == 0 || fs == 0) {
            if (vs != 0) {
                glDeleteShader(vs);
            }
            if (fs != 0) {
                glDeleteShader(fs);
            }
            DestroyGLESLocked();
            return false;
        }

        m_glProgram = glCreateProgram();
        glAttachShader(m_glProgram, vs);
        glAttachShader(m_glProgram, fs);
        glLinkProgram(m_glProgram);
        glDeleteShader(vs);
        glDeleteShader(fs);

        GLint linked = GL_FALSE;
        glGetProgramiv(m_glProgram, GL_LINK_STATUS, &linked);
        if (linked != GL_TRUE) {
            LogProgramErrorLocked(m_glProgram);
            DestroyGLESLocked();
            return false;
        }
        m_glUniformFrame = glGetUniformLocation(m_glProgram, "uFrame");
        m_glUniformTexelSize = glGetUniformLocation(m_glProgram, "uTexelSize");
        m_glUniformCropRect = glGetUniformLocation(m_glProgram, "uCropRect");
        m_glUniformCrtEnabled = glGetUniformLocation(m_glProgram, "uCrtEnabled");

        glGenTextures(1, &m_glTexture);
        glBindTexture(GL_TEXTURE_2D, m_glTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        m_windowWidth = 0;
        m_windowHeight = 0;
        UploadGLESTextureLocked(width, height, nullptr);
        return true;
    }

    void PresentGLESFrameLocked(const uint32 *fb, uint32 width, uint32 height) {
        if (!EnsureGLESLocked(width, height)) {
            PresentSoftwareFrameFallbackLocked(fb, width, height);
            return;
        }
        if (eglMakeCurrent(m_eglDisplay, m_eglSurface, m_eglSurface, m_eglContext) == EGL_FALSE) {
            DestroyGLESLocked();
            PresentSoftwareFrameFallbackLocked(fb, width, height);
            return;
        }

        UploadGLESTextureLocked(width, height, fb);

        EGLint surfaceWidth = 0;
        EGLint surfaceHeight = 0;
        eglQuerySurface(m_eglDisplay, m_eglSurface, EGL_WIDTH, &surfaceWidth);
        eglQuerySurface(m_eglDisplay, m_eglSurface, EGL_HEIGHT, &surfaceHeight);
        const bool crtEnabled = m_crtEnabled.load(std::memory_order_relaxed);
        const bool bezelEnabled = m_bezelEnabled.load(std::memory_order_relaxed);
        const DisplayViewport viewport = CalculateViewportLocked(surfaceWidth, surfaceHeight);
        glClearColor(bezelEnabled ? 0.015f : 0.0f, bezelEnabled ? 0.018f : 0.0f,
                     bezelEnabled ? 0.022f : 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glViewport(viewport.x, viewport.y, viewport.width, viewport.height);
        glDisable(GL_BLEND);
        glUseProgram(m_glProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, m_glTexture);
        glUniform1i(m_glUniformFrame, 0);
        glUniform2f(m_glUniformTexelSize, 1.0f / static_cast<float>(std::max<uint32>(width, 1)),
                    1.0f / static_cast<float>(std::max<uint32>(height, 1)));
        const SourceCrop crop = CalculateSourceCropLocked(width, height);
        glUniform4f(m_glUniformCropRect,
                    static_cast<float>(crop.x) / static_cast<float>(std::max<uint32>(width, 1)),
                    static_cast<float>(crop.y) / static_cast<float>(std::max<uint32>(height, 1)),
                    static_cast<float>(crop.width) / static_cast<float>(std::max<uint32>(width, 1)),
                    static_cast<float>(crop.height) / static_cast<float>(std::max<uint32>(height, 1)));
        glUniform1i(m_glUniformCrtEnabled, crtEnabled ? 1 : 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        eglSwapBuffers(m_eglDisplay, m_eglSurface);
        eglMakeCurrent(m_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }

    void PresentSoftwareFrameFallbackLocked(const uint32 *fb, uint32 width, uint32 height) {
        const uint32 targetWidth = m_surfaceWidth != 0 ? m_surfaceWidth : static_cast<uint32>(std::max(1, ANativeWindow_getWidth(m_window)));
        const uint32 targetHeight = m_surfaceHeight != 0 ? m_surfaceHeight : static_cast<uint32>(std::max(1, ANativeWindow_getHeight(m_window)));
        if (m_windowWidth != targetWidth || m_windowHeight != targetHeight) {
            ANativeWindow_setBuffersGeometry(m_window, static_cast<int32_t>(targetWidth), static_cast<int32_t>(targetHeight),
                                             WINDOW_FORMAT_RGBA_8888);
            m_windowWidth = targetWidth;
            m_windowHeight = targetHeight;
        }

        ANativeWindow_Buffer buffer{};
        if (ANativeWindow_lock(m_window, &buffer, nullptr) != 0) {
            return;
        }
        BlitSoftwareFrameToBufferLocked(fb, width, height, buffer);
        ANativeWindow_unlockAndPost(m_window);
    }

    DisplayViewport CalculateViewportLocked(int surfaceWidth, int surfaceHeight) const {
        const bool bezelEnabled = m_bezelEnabled.load(std::memory_order_relaxed);
        const int aspectMode = m_aspectMode.load(std::memory_order_relaxed);
        const float targetAspect = aspectMode == static_cast<int>(AspectMode::Wide16_9)
                ? (16.0f / 9.0f)
                : (4.0f / 3.0f);
        const bool fillBezelHeight = bezelEnabled && m_cropEnabled.load(std::memory_order_relaxed);
        int maxX = 0;
        int maxY = 0;
        int maxWidth = surfaceWidth;
        int maxHeight = surfaceHeight;
        if (fillBezelHeight && aspectMode == static_cast<int>(AspectMode::Standard4_3)) {
            maxX = surfaceWidth * 241 / 1920;
            maxY = surfaceHeight * 2 / 1080;
            maxWidth = std::max(1, surfaceWidth * 1436 / 1920);
            maxHeight = std::max(1, surfaceHeight * 1077 / 1080);
        } else if (bezelEnabled && !fillBezelHeight) {
            maxWidth = std::max(1, surfaceWidth * 3 / 4);
        }

        DisplayViewport viewport{};
        if (fillBezelHeight) {
            viewport.height = maxHeight;
            viewport.width = static_cast<int>(static_cast<float>(viewport.height) * targetAspect);
            if (viewport.width > surfaceWidth) {
                viewport.width = surfaceWidth;
                viewport.height = static_cast<int>(static_cast<float>(viewport.width) / targetAspect);
            }
        } else {
            viewport.width = maxWidth;
            viewport.height = static_cast<int>(static_cast<float>(viewport.width) / targetAspect);
            if (viewport.height > maxHeight) {
                viewport.height = maxHeight;
                viewport.width = static_cast<int>(static_cast<float>(viewport.height) * targetAspect);
            }
        }
        viewport.width = std::max(1, std::min(viewport.width, surfaceWidth));
        viewport.height = std::max(1, std::min(viewport.height, surfaceHeight));
        viewport.x = maxX + (maxWidth - viewport.width) / 2;
        viewport.y = maxY + (maxHeight - viewport.height) / 2;
        return viewport;
    }

    SourceCrop CalculateSourceCropLocked(uint32 width, uint32 height) const {
        SourceCrop crop{};
        crop.width = std::max<uint32>(1, width);
        crop.height = std::max<uint32>(1, height);
        return crop;
    }

    void BlitSoftwareFrameToBufferLocked(const uint32 *fb, uint32 width, uint32 height,
                                         const ANativeWindow_Buffer &buffer) const {
        const int surfaceWidth = std::max(1, buffer.width);
        const int surfaceHeight = std::max(1, buffer.height);
        const DisplayViewport viewport = CalculateViewportLocked(surfaceWidth, surfaceHeight);
        const SourceCrop crop = CalculateSourceCropLocked(width, height);
        const uint32 background = m_bezelEnabled.load(std::memory_order_relaxed) ? 0xFF050505u : 0xFF000000u;
        auto *dst = static_cast<uint32 *>(buffer.bits);

        for (int y = 0; y < surfaceHeight; ++y) {
            std::fill(dst + static_cast<size_t>(y) * buffer.stride,
                      dst + static_cast<size_t>(y) * buffer.stride + surfaceWidth,
                      background);
        }

        for (int y = 0; y < viewport.height; ++y) {
            const uint32 srcY = crop.y + static_cast<uint32>((static_cast<uint64_t>(y) * crop.height) / viewport.height);
            const uint32 *srcRow = fb + static_cast<size_t>(srcY) * width;
            uint32 *dstRow = dst + static_cast<size_t>(viewport.y + y) * buffer.stride + viewport.x;
            for (int x = 0; x < viewport.width; ++x) {
                const uint32 srcX = crop.x + static_cast<uint32>((static_cast<uint64_t>(x) * crop.width) / viewport.width);
                dstRow[x] = srcRow[srcX];
            }
        }
    }

    const uint32 *ApplyVulkanCrtFilterLocked(const uint32 *fb, uint32 width, uint32 height) {
        const size_t pixelCount = static_cast<size_t>(width) * height;
        if (m_vkCrtFrame.size() != pixelCount) {
            m_vkCrtFrame.resize(pixelCount);
        }

        const float invWidth = width > 1 ? 1.0f / static_cast<float>(width - 1) : 1.0f;
        const float invHeight = height > 1 ? 1.0f / static_cast<float>(height - 1) : 1.0f;
        for (uint32 y = 0; y < height; ++y) {
            const float ny = static_cast<float>(y) * invHeight - 0.5f;
            const float scanline = (y & 1u) != 0 ? 0.74f : 1.0f;
            for (uint32 x = 0; x < width; ++x) {
                const uint32 pixel = fb[static_cast<size_t>(y) * width + x];
                float r = static_cast<float>(pixel & 0xFFu);
                float g = static_cast<float>((pixel >> 8u) & 0xFFu);
                float b = static_cast<float>((pixel >> 16u) & 0xFFu);
                const uint32 a = (pixel >> 24u) & 0xFFu;

                float maskR = 0.90f;
                float maskG = 0.90f;
                float maskB = 0.90f;
                switch (x % 3u) {
                case 0: maskR = 1.05f; break;
                case 1: maskG = 1.05f; break;
                default: maskB = 1.05f; break;
                }

                const float nx = static_cast<float>(x) * invWidth - 0.5f;
                const float vignette = std::clamp(1.0f - (nx * nx + ny * ny) * 0.92f, 0.62f, 1.0f);
                const float gain = scanline * vignette * 1.08f;
                r = std::clamp(r * maskR * gain, 0.0f, 255.0f);
                g = std::clamp(g * maskG * gain, 0.0f, 255.0f);
                b = std::clamp(b * maskB * gain, 0.0f, 255.0f);

                m_vkCrtFrame[static_cast<size_t>(y) * width + x] =
                        (a << 24u)
                        | (static_cast<uint32>(b + 0.5f) << 16u)
                        | (static_cast<uint32>(g + 0.5f) << 8u)
                        | static_cast<uint32>(r + 0.5f);
            }
        }
        return m_vkCrtFrame.data();
    }

    void UploadGLESTextureLocked(uint32 width, uint32 height, const uint32 *fb) {
        glBindTexture(GL_TEXTURE_2D, m_glTexture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        if (m_windowWidth != width || m_windowHeight != height) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, static_cast<GLsizei>(width), static_cast<GLsizei>(height), 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, fb);
            m_windowWidth = width;
            m_windowHeight = height;
        } else if (fb != nullptr) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height),
                            GL_RGBA, GL_UNSIGNED_BYTE, fb);
        }
    }

    GLuint CompileShaderLocked(GLenum type, const char *source) {
        const GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (compiled != GL_TRUE) {
            LogShaderErrorLocked(shader);
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    void LogShaderErrorLocked(GLuint shader) {
        GLint length = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
        if (length <= 1) {
            return;
        }
        std::string log(static_cast<size_t>(length), '\0');
        glGetShaderInfoLog(shader, length, nullptr, log.data());
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "GL shader error: %s", log.c_str());
    }

    void LogProgramErrorLocked(GLuint program) {
        GLint length = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
        if (length <= 1) {
            return;
        }
        std::string log(static_cast<size_t>(length), '\0');
        glGetProgramInfoLog(program, length, nullptr, log.data());
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "GL program error: %s", log.c_str());
    }

    void DestroyGLESLocked() {
        if (m_eglDisplay == EGL_NO_DISPLAY) {
            return;
        }
        if (m_eglSurface != EGL_NO_SURFACE && m_eglContext != EGL_NO_CONTEXT) {
            eglMakeCurrent(m_eglDisplay, m_eglSurface, m_eglSurface, m_eglContext);
        }
        if (m_glTexture != 0) {
            glDeleteTextures(1, &m_glTexture);
            m_glTexture = 0;
        }
        if (m_glProgram != 0) {
            glDeleteProgram(m_glProgram);
            m_glProgram = 0;
        }
        m_glUniformFrame = -1;
        m_glUniformTexelSize = -1;
        m_glUniformCropRect = -1;
        m_glUniformCrtEnabled = -1;
        if (m_eglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(m_eglDisplay, m_eglContext);
            m_eglContext = EGL_NO_CONTEXT;
        }
        if (m_eglSurface != EGL_NO_SURFACE) {
            eglDestroySurface(m_eglDisplay, m_eglSurface);
            m_eglSurface = EGL_NO_SURFACE;
        }
        eglMakeCurrent(m_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglTerminate(m_eglDisplay);
        m_eglDisplay = EGL_NO_DISPLAY;
        m_windowWidth = 0;
        m_windowHeight = 0;
    }

    void DestroyVulkanLocked() {
        if (m_vkDevice != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(m_vkDevice);
        }
        if (m_vkDevice != VK_NULL_HANDLE && m_vkFrameFence != VK_NULL_HANDLE) {
            vkDestroyFence(m_vkDevice, m_vkFrameFence, nullptr);
            m_vkFrameFence = VK_NULL_HANDLE;
        }
        if (m_vkDevice != VK_NULL_HANDLE && m_vkRenderFinished != VK_NULL_HANDLE) {
            vkDestroySemaphore(m_vkDevice, m_vkRenderFinished, nullptr);
            m_vkRenderFinished = VK_NULL_HANDLE;
        }
        if (m_vkDevice != VK_NULL_HANDLE && m_vkImageAvailable != VK_NULL_HANDLE) {
            vkDestroySemaphore(m_vkDevice, m_vkImageAvailable, nullptr);
            m_vkImageAvailable = VK_NULL_HANDLE;
        }
        if (m_vkDevice != VK_NULL_HANDLE && m_vkSourceImage != VK_NULL_HANDLE) {
            vkDestroyImage(m_vkDevice, m_vkSourceImage, nullptr);
            m_vkSourceImage = VK_NULL_HANDLE;
        }
        if (m_vkDevice != VK_NULL_HANDLE && m_vkSourceMemory != VK_NULL_HANDLE) {
            vkFreeMemory(m_vkDevice, m_vkSourceMemory, nullptr);
            m_vkSourceMemory = VK_NULL_HANDLE;
        }
        if (m_vkDevice != VK_NULL_HANDLE && m_vkStagingBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(m_vkDevice, m_vkStagingBuffer, nullptr);
            m_vkStagingBuffer = VK_NULL_HANDLE;
        }
        if (m_vkDevice != VK_NULL_HANDLE && m_vkStagingMemory != VK_NULL_HANDLE) {
            vkFreeMemory(m_vkDevice, m_vkStagingMemory, nullptr);
            m_vkStagingMemory = VK_NULL_HANDLE;
        }
        if (m_vkDevice != VK_NULL_HANDLE && m_vkCommandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(m_vkDevice, m_vkCommandPool, nullptr);
            m_vkCommandPool = VK_NULL_HANDLE;
            m_vkCommandBuffer = VK_NULL_HANDLE;
        }
        if (m_vkDevice != VK_NULL_HANDLE && m_vkSwapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(m_vkDevice, m_vkSwapchain, nullptr);
            m_vkSwapchain = VK_NULL_HANDLE;
        }
        m_vkSwapchainImages.clear();
        if (m_vkDevice != VK_NULL_HANDLE) {
            vkDestroyDevice(m_vkDevice, nullptr);
            m_vkDevice = VK_NULL_HANDLE;
        }
        if (m_vkInstance != VK_NULL_HANDLE && m_vkSurface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(m_vkInstance, m_vkSurface, nullptr);
            m_vkSurface = VK_NULL_HANDLE;
        }
        if (m_vkInstance != VK_NULL_HANDLE) {
            vkDestroyInstance(m_vkInstance, nullptr);
            m_vkInstance = VK_NULL_HANDLE;
        }
        m_vkPhysicalDevice = VK_NULL_HANDLE;
        m_vkQueue = VK_NULL_HANDLE;
        m_vkQueueFamily = UINT32_MAX;
        m_vkSourceWidth = 0;
        m_vkSourceHeight = 0;
        m_vkSourceLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (m_backend.load(std::memory_order_relaxed) == static_cast<int>(RendererBackend::Vulkan)) {
            m_windowWidth = 0;
            m_windowHeight = 0;
        }
    }

    void MarkVulkanUnavailableLocked(const char *message) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Vulkan presenter unavailable: %s", message);
        DestroyVulkanLocked();
        m_vulkanUnavailable = true;
        m_backend.store(static_cast<int>(RendererBackend::Software), std::memory_order_relaxed);
    }

    uint32_t FindVulkanMemoryTypeLocked(uint32_t typeBits, VkMemoryPropertyFlags properties) const {
        VkPhysicalDeviceMemoryProperties memoryProperties{};
        vkGetPhysicalDeviceMemoryProperties(m_vkPhysicalDevice, &memoryProperties);
        for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i) {
            if ((typeBits & (1u << i)) != 0
                    && (memoryProperties.memoryTypes[i].propertyFlags & properties) == properties) {
                return i;
            }
        }
        return UINT32_MAX;
    }

    bool CreateVulkanBufferLocked(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties,
                                  VkBuffer &buffer, VkDeviceMemory &memory) {
        VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bufferInfo.size = size;
        bufferInfo.usage = usage;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(m_vkDevice, &bufferInfo, nullptr, &buffer) != VK_SUCCESS) {
            return false;
        }
        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(m_vkDevice, buffer, &requirements);
        uint32_t memoryType = FindVulkanMemoryTypeLocked(requirements.memoryTypeBits, properties);
        if (memoryType == UINT32_MAX) {
            return false;
        }
        VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocInfo.allocationSize = requirements.size;
        allocInfo.memoryTypeIndex = memoryType;
        if (vkAllocateMemory(m_vkDevice, &allocInfo, nullptr, &memory) != VK_SUCCESS) {
            return false;
        }
        return vkBindBufferMemory(m_vkDevice, buffer, memory, 0) == VK_SUCCESS;
    }

    bool CreateVulkanImageLocked(uint32 width, uint32 height, VkFormat format, VkImageUsageFlags usage,
                                 VkImage &image, VkDeviceMemory &memory) {
        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = format;
        imageInfo.extent = {width, height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = usage;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (vkCreateImage(m_vkDevice, &imageInfo, nullptr, &image) != VK_SUCCESS) {
            return false;
        }
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(m_vkDevice, image, &requirements);
        uint32_t memoryType = FindVulkanMemoryTypeLocked(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (memoryType == UINT32_MAX) {
            return false;
        }
        VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocInfo.allocationSize = requirements.size;
        allocInfo.memoryTypeIndex = memoryType;
        if (vkAllocateMemory(m_vkDevice, &allocInfo, nullptr, &memory) != VK_SUCCESS) {
            return false;
        }
        return vkBindImageMemory(m_vkDevice, image, memory, 0) == VK_SUCCESS;
    }

    void TransitionImageLocked(VkImage image, VkImageLayout oldLayout, VkImageLayout newLayout,
                               VkAccessFlags srcAccess, VkAccessFlags dstAccess) {
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.oldLayout = oldLayout;
        barrier.newLayout = newLayout;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
        barrier.srcAccessMask = srcAccess;
        barrier.dstAccessMask = dstAccess;
        vkCmdPipelineBarrier(m_vkCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                             0, nullptr, 0, nullptr, 1, &barrier);
    }

    static const char *BackendName(RendererBackend backend) {
        switch (backend) {
        case RendererBackend::Software: return "software";
        case RendererBackend::OpenGLES: return "opengles";
        case RendererBackend::Vulkan: return "vulkan";
        default: return "unknown";
        }
    }

    static ymir::peripheral::Button ToYmirButton(PadButton button) {
        using Button = ymir::peripheral::Button;
        switch (button) {
        case PadButton::Up: return Button::Up;
        case PadButton::Down: return Button::Down;
        case PadButton::Left: return Button::Left;
        case PadButton::Right: return Button::Right;
        case PadButton::Start: return Button::Start;
        case PadButton::A: return Button::A;
        case PadButton::B: return Button::B;
        case PadButton::C: return Button::C;
        case PadButton::X: return Button::X;
        case PadButton::Y: return Button::Y;
        case PadButton::Z: return Button::Z;
        case PadButton::L: return Button::L;
        case PadButton::R: return Button::R;
        default: return Button::None;
        }
    }

    mutable std::mutex m_emuMutex;
    mutable std::mutex m_stateMutex;
    mutable std::mutex m_windowMutex;
    mutable std::mutex m_inputMutex;
    ymir::Saturn m_saturn;
    AndroidAudioOutput m_audio;
    ANativeWindow *m_window = nullptr;
    uint32 m_windowWidth = 0;
    uint32 m_windowHeight = 0;
    uint32 m_surfaceWidth = 0;
    uint32 m_surfaceHeight = 0;
    EGLDisplay m_eglDisplay = EGL_NO_DISPLAY;
    EGLSurface m_eglSurface = EGL_NO_SURFACE;
    EGLContext m_eglContext = EGL_NO_CONTEXT;
    GLuint m_glProgram = 0;
    GLuint m_glTexture = 0;
    GLint m_glUniformFrame = -1;
    GLint m_glUniformTexelSize = -1;
    GLint m_glUniformCropRect = -1;
    GLint m_glUniformCrtEnabled = -1;
    bool m_glesUnavailable = false;
    VkInstance m_vkInstance = VK_NULL_HANDLE;
    VkSurfaceKHR m_vkSurface = VK_NULL_HANDLE;
    VkPhysicalDevice m_vkPhysicalDevice = VK_NULL_HANDLE;
    VkDevice m_vkDevice = VK_NULL_HANDLE;
    VkQueue m_vkQueue = VK_NULL_HANDLE;
    uint32_t m_vkQueueFamily = UINT32_MAX;
    VkSwapchainKHR m_vkSwapchain = VK_NULL_HANDLE;
    VkFormat m_vkSwapchainFormat = VK_FORMAT_UNDEFINED;
    std::vector<VkImage> m_vkSwapchainImages;
    VkCommandPool m_vkCommandPool = VK_NULL_HANDLE;
    VkCommandBuffer m_vkCommandBuffer = VK_NULL_HANDLE;
    VkSemaphore m_vkImageAvailable = VK_NULL_HANDLE;
    VkSemaphore m_vkRenderFinished = VK_NULL_HANDLE;
    VkFence m_vkFrameFence = VK_NULL_HANDLE;
    VkBuffer m_vkStagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_vkStagingMemory = VK_NULL_HANDLE;
    VkImage m_vkSourceImage = VK_NULL_HANDLE;
    VkDeviceMemory m_vkSourceMemory = VK_NULL_HANDLE;
    VkImageLayout m_vkSourceLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    uint32 m_vkSourceWidth = 0;
    uint32 m_vkSourceHeight = 0;
    std::vector<uint32> m_vkCrtFrame;
    bool m_vulkanUnavailable = false;
    std::atomic_int m_requestedBackend = static_cast<int>(RendererBackend::Software);
    std::atomic_int m_backend = static_cast<int>(RendererBackend::Software);
    std::atomic_int m_aspectMode = static_cast<int>(AspectMode::Standard4_3);
    std::atomic_bool m_bezelEnabled = false;
    std::atomic_bool m_crtEnabled = false;
    std::atomic_bool m_cropEnabled = false;
    std::atomic_bool m_presentationPaused = false;
    std::string m_customVulkanDriverPath;
    std::string m_biosPath;
    std::string m_internalBackupPath;
    std::string m_gamesFolderUri;
    std::string m_gamePath;
    bool m_biosLoaded = false;
    std::atomic_bool m_running = false;
    std::thread m_thread;
    std::atomic_uint64_t m_frames = 0;
    std::atomic<double> m_fps = 0.0;
    uint64_t m_lastFpsFrame = 0;
    std::chrono::steady_clock::time_point m_lastFpsTime = std::chrono::steady_clock::now();
    ymir::peripheral::Button m_padButtons = ymir::peripheral::Button::Default;
};

AndroidYmirRuntime *FromHandle(jlong handle) {
    return reinterpret_cast<AndroidYmirRuntime *>(handle);
}

void EnsureSmpcPersistentStateInitialized(const std::string &path) {
    if (path.empty()) {
        return;
    }

    std::array<uint8, kSmpcPersistentStateSize> data{};
    data[0] = 1;

    {
        std::ifstream in{path, std::ios::binary};
        if (in) {
            in.read(reinterpret_cast<char *>(data.data()), static_cast<std::streamsize>(data.size()));
            if (in.gcount() >= 9 && data[0] == 1 && data[8] != 0) {
                return;
            }
            data[0] = 1;
        }
    }

    data[8] = 1;
    const sint64 hostTimestamp = static_cast<sint64>(std::time(nullptr));
    std::memcpy(data.data() + 17, &hostTimestamp, sizeof(hostTimestamp));

    std::ofstream out{path, std::ios::binary | std::ios::trunc};
    if (out) {
        out.write(reinterpret_cast<const char *>(data.data()), static_cast<std::streamsize>(data.size()));
    }
}

jstring ToJString(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_coreInfo(JNIEnv *env, jclass) {
    return ToJString(env, std::string{"Ymir "} + Ymir_VERSION);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_create(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new AndroidYmirRuntime());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_destroy(JNIEnv *, jclass, jlong handle) {
    delete FromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_setSurface(JNIEnv *env, jclass, jlong handle, jobject surface) {
    auto *runtime = FromHandle(handle);
    if (runtime == nullptr || surface == nullptr) {
        return;
    }
    runtime->SetSurface(ANativeWindow_fromSurface(env, surface));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_setSurfaceSize(JNIEnv *, jclass, jlong handle, jint width,
                                                                jint height) {
    if (auto *runtime = FromHandle(handle)) {
        runtime->SetSurfaceSize(width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_clearSurface(JNIEnv *, jclass, jlong handle) {
    if (auto *runtime = FromHandle(handle)) {
        runtime->ClearSurface();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_setRendererBackend(JNIEnv *, jclass, jlong handle, jint backend) {
    if (auto *runtime = FromHandle(handle)) {
        runtime->SetRendererBackend(static_cast<RendererBackend>(backend));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_setDisplayOptions(JNIEnv *, jclass, jlong handle, jint aspectMode,
                                                                   jboolean bezelEnabled, jboolean crtEnabled,
                                                                   jboolean cropEnabled) {
    if (auto *runtime = FromHandle(handle)) {
        runtime->SetDisplayOptions(static_cast<AspectMode>(aspectMode), bezelEnabled == JNI_TRUE,
                                   crtEnabled == JNI_TRUE, cropEnabled == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_setPresentationPaused(JNIEnv *, jclass, jlong handle,
                                                                       jboolean paused) {
    if (auto *runtime = FromHandle(handle)) {
        runtime->SetPresentationPaused(paused == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_setAudioMuted(JNIEnv *, jclass, jlong handle, jboolean muted) {
    if (auto *runtime = FromHandle(handle)) {
        runtime->SetAudioMuted(muted == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_setCustomVulkanDriverPath(JNIEnv *env, jclass, jlong handle,
                                                                           jstring path) {
    auto *runtime = FromHandle(handle);
    if (runtime == nullptr || path == nullptr) {
        return;
    }
    const char *chars = env->GetStringUTFChars(path, nullptr);
    runtime->SetCustomVulkanDriverPath(chars != nullptr ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(path, chars);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_loadBiosFile(JNIEnv *env, jclass, jlong handle, jstring path) {
    auto *runtime = FromHandle(handle);
    if (runtime == nullptr || path == nullptr) {
        return ToJString(env, "BIOS load failed: runtime not available");
    }
    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::string result = runtime->LoadBiosFile(chars != nullptr ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(path, chars);
    }
    return ToJString(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_loadInternalBackupMemory(JNIEnv *env, jclass, jlong handle,
                                                                          jstring path) {
    auto *runtime = FromHandle(handle);
    if (runtime == nullptr || path == nullptr) {
        return ToJString(env, "Backup memory failed: runtime not available");
    }
    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::string result = runtime->LoadInternalBackupMemory(chars != nullptr ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(path, chars);
    }
    return ToJString(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_saveInternalBackupMemory(JNIEnv *env, jclass, jlong handle) {
    if (auto *runtime = FromHandle(handle)) {
        return ToJString(env, runtime->SaveInternalBackupMemory());
    }
    return ToJString(env, "Backup memory save failed: runtime not available");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_loadSmpcPersistentState(JNIEnv *env, jclass, jlong handle,
                                                                         jstring path) {
    auto *runtime = FromHandle(handle);
    if (runtime == nullptr || path == nullptr) {
        return ToJString(env, "SMPC state failed: runtime not available");
    }
    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::string result = runtime->LoadSmpcPersistentState(chars != nullptr ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(path, chars);
    }
    return ToJString(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_saveSmpcPersistentState(JNIEnv *env, jclass, jlong handle) {
    if (auto *runtime = FromHandle(handle)) {
        return ToJString(env, runtime->SaveSmpcPersistentState());
    }
    return ToJString(env, "SMPC state save failed: runtime not available");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_loadGameFile(JNIEnv *env, jclass, jlong handle, jstring path) {
    auto *runtime = FromHandle(handle);
    if (runtime == nullptr || path == nullptr) {
        return ToJString(env, "Game load failed: runtime not available");
    }
    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::string result = runtime->LoadGameFile(chars != nullptr ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(path, chars);
    }
    return ToJString(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_setGamesFolderUri(JNIEnv *env, jclass, jlong handle, jstring uri) {
    auto *runtime = FromHandle(handle);
    if (runtime == nullptr || uri == nullptr) {
        return;
    }
    const char *chars = env->GetStringUTFChars(uri, nullptr);
    runtime->SetGamesFolderUri(chars != nullptr ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(uri, chars);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_setPadButton(JNIEnv *, jclass, jlong handle, jint button,
                                                              jboolean pressed) {
    if (auto *runtime = FromHandle(handle)) {
        runtime->SetPadButton(static_cast<PadButton>(button), pressed == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_reset(JNIEnv *, jclass, jlong handle) {
    if (auto *runtime = FromHandle(handle)) {
        runtime->Reset();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_runFrame(JNIEnv *, jclass, jlong handle) {
    if (auto *runtime = FromHandle(handle)) {
        return runtime->RunFrame() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_start(JNIEnv *, jclass, jlong handle) {
    if (auto *runtime = FromHandle(handle)) {
        runtime->Start();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_stop(JNIEnv *, jclass, jlong handle) {
    if (auto *runtime = FromHandle(handle)) {
        runtime->Stop();
    }
}

extern "C" JNIEXPORT jdouble JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_fps(JNIEnv *, jclass, jlong handle) {
    if (auto *runtime = FromHandle(handle)) {
        return runtime->GetFps();
    }
    return 0.0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_strikerx3_ymir_android_YmirNative_status(JNIEnv *env, jclass, jlong handle) {
    if (auto *runtime = FromHandle(handle)) {
        return ToJString(env, runtime->Status());
    }
    return ToJString(env, "runtime=null");
}
