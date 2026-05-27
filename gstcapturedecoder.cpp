#include "gstcapturedecoder.h"
#include "capturedebuglog.h"
#include <QDebug>
#include <gst/gst.h>
#include <gst/app/gstappsrc.h>
#include <gst/app/gstappsink.h>

GstCaptureDecoder::GstCaptureDecoder()
{
}

GstCaptureDecoder::~GstCaptureDecoder()
{
    cleanup();
}

bool GstCaptureDecoder::ensurePipeline()
{
    if (m_pipeline) return m_ready;

    CaptureDebugScope scope("DEC", "ensurePipeline", 200);

    QString desc =
        "appsrc name=src "
        "! h264parse config-interval=-1 "
        "! avdec_h264 "
        "! videoconvert "
        "! video/x-raw,format=BGRA "
        "! appsink name=sink";

    GError *err = nullptr;
    m_pipeline = gst_parse_launch(desc.toUtf8().constData(), &err);
    if (err) {
        captureDebugLog("DEC", QString("ensurePipeline FAIL: %1").arg(err->message));
        g_error_free(err);
        if (m_pipeline) { gst_object_unref(m_pipeline); m_pipeline = nullptr; }
        return false;
    }

    m_appsrc = gst_bin_get_by_name(GST_BIN(m_pipeline), "src");
    m_appsink = gst_bin_get_by_name(GST_BIN(m_pipeline), "sink");

    GstCaps *caps = gst_caps_from_string(
        "video/x-h264, stream-format=byte-stream, alignment=nal");
    g_object_set(m_appsrc,
        "caps", caps,
        "format", GST_FORMAT_TIME,
        "is-live", FALSE,
        "stream-type", 0,
        nullptr);
    gst_caps_unref(caps);

    g_object_set(m_appsink,
        "sync", FALSE,
        "max-buffers", 1,
        "drop", TRUE,
        nullptr);

    GstStateChangeReturn ret = gst_element_set_state(m_pipeline, GST_STATE_PLAYING);
    if (ret == GST_STATE_CHANGE_FAILURE) {
        captureDebugLog("DEC", "ensurePipeline FAIL: set_state PLAYING failed");
        cleanup();
        return false;
    }

    m_ready = true;
    m_pts = 0;
    captureDebugLog("DEC", "ensurePipeline OK");
    return true;
}

void GstCaptureDecoder::cleanup()
{
    if (m_pipeline) gst_element_set_state(m_pipeline, GST_STATE_NULL);
    if (m_appsrc)   { gst_object_unref(m_appsrc);   m_appsrc = nullptr; }
    if (m_appsink)  { gst_object_unref(m_appsink);  m_appsink = nullptr; }
    if (m_pipeline) { gst_object_unref(m_pipeline); m_pipeline = nullptr; }
    m_ready = false;
    m_pts = 0;
}

void GstCaptureDecoder::drainAppsink()
{
    if (!m_appsink) return;
    int drained = 0;
    while (true) {
        GstSample *s = gst_app_sink_try_pull_sample(GST_APP_SINK(m_appsink), 0);
        if (!s) break;
        gst_sample_unref(s);
        drained++;
    }
    if (drained > 0) {
        captureDebugLog("DEC", QString("drainAppsink dropped %1 samples").arg(drained));
    }
}

bool GstCaptureDecoder::pushNalu(const QByteArray &naluData)
{
    QMutexLocker lock(&m_mutex);

    if (naluData.isEmpty()) return false;
    if (!ensurePipeline()) return false;

    GstBuffer *buffer = gst_buffer_new_allocate(nullptr, naluData.size(), nullptr);
    gst_buffer_fill(buffer, 0, naluData.constData(), naluData.size());

    GST_BUFFER_PTS(buffer) = m_pts;
    GST_BUFFER_DTS(buffer) = m_pts;
    GST_BUFFER_DURATION(buffer) = GST_SECOND / 30;
    m_pts += GST_SECOND / 30;

    GstFlowReturn flowRet = gst_app_src_push_buffer(GST_APP_SRC(m_appsrc), buffer);
    if (flowRet != GST_FLOW_OK) {
        captureDebugLog("DEC", QString("pushNalu FAIL flowRet=%1 %2")
            .arg(flowRet).arg(captureDebugNaluPreview(naluData)));
    }
    return (flowRet == GST_FLOW_OK);
}

QImage GstCaptureDecoder::pullLatest()
{
    QMutexLocker lock(&m_mutex);
    if (!m_appsink) return QImage();

    CaptureDebugScope scope("DEC", "pullLatest", 100);

    GstSample *sample = gst_app_sink_try_pull_sample(
        GST_APP_SINK(m_appsink), 500 * GST_MSECOND);

    if (!sample) {
        captureDebugLog("DEC", "pullLatest TIMEOUT 500ms");
        return QImage();
    }

    GstCaps *caps = gst_sample_get_caps(sample);
    GstStructure *s = gst_caps_get_structure(caps, 0);
    int w = 0, h = 0;
    gst_structure_get_int(s, "width", &w);
    gst_structure_get_int(s, "height", &h);

    if (w <= 0 || h <= 0) {
        gst_sample_unref(sample);
        captureDebugLog("DEC", QString("pullLatest bad size %1x%2").arg(w).arg(h));
        return QImage();
    }

    GstBuffer *buf = gst_sample_get_buffer(sample);
    GstMapInfo map;
    if (!gst_buffer_map(buf, &map, GST_MAP_READ)) {
        gst_sample_unref(sample);
        return QImage();
    }

    QImage img(map.data, w, h, w * 4, QImage::Format_ARGB32);
    QImage result = img.copy();

    gst_buffer_unmap(buf, &map);
    gst_sample_unref(sample);

    captureDebugLog("DEC", QString("pullLatest OK %1x%2").arg(w).arg(h));
    return result;
}

QImage GstCaptureDecoder::decodeNalu(const QByteArray &naluData)
{
    QMutexLocker lock(&m_mutex);

    if (naluData.isEmpty()) return QImage();
    if (!ensurePipeline()) return QImage();

    CaptureDebugScope scope("DEC", "decodeNalu", 100);

    GstBuffer *buffer = gst_buffer_new_allocate(nullptr, naluData.size(), nullptr);
    gst_buffer_fill(buffer, 0, naluData.constData(), naluData.size());

    GST_BUFFER_PTS(buffer) = m_pts;
    GST_BUFFER_DTS(buffer) = m_pts;
    GST_BUFFER_DURATION(buffer) = GST_SECOND / 30;
    m_pts += GST_SECOND / 30;

    GstFlowReturn flowRet = gst_app_src_push_buffer(GST_APP_SRC(m_appsrc), buffer);
    if (flowRet != GST_FLOW_OK) {
        captureDebugLog("DEC", QString("decodeNalu push FAIL flowRet=%1 %2")
            .arg(flowRet).arg(captureDebugNaluPreview(naluData)));
        return QImage();
    }

    scope.checkpoint("after push");

    GstSample *sample = gst_app_sink_try_pull_sample(
        GST_APP_SINK(m_appsink), 500 * GST_MSECOND);

    if (!sample) {
        captureDebugLog("DEC", QString("decodeNalu pull TIMEOUT %1")
            .arg(captureDebugNaluPreview(naluData)));
        return QImage();
    }

    GstCaps *caps = gst_sample_get_caps(sample);
    GstStructure *s = gst_caps_get_structure(caps, 0);
    int w = 0, h = 0;
    gst_structure_get_int(s, "width", &w);
    gst_structure_get_int(s, "height", &h);

    if (w <= 0 || h <= 0) {
        gst_sample_unref(sample);
        captureDebugLog("DEC", QString("decodeNalu bad size %1x%2").arg(w).arg(h));
        return QImage();
    }

    GstBuffer *buf = gst_sample_get_buffer(sample);
    GstMapInfo map;
    if (!gst_buffer_map(buf, &map, GST_MAP_READ)) {
        gst_sample_unref(sample);
        captureDebugLog("DEC", "decodeNalu map buffer FAIL");
        return QImage();
    }

    QImage img(map.data, w, h, w * 4, QImage::Format_ARGB32);
    QImage result = img.copy();

    gst_buffer_unmap(buf, &map);
    gst_sample_unref(sample);

    captureDebugLog("DEC", QString("decodeNalu OK %1x%2 %3")
        .arg(w).arg(h).arg(captureDebugNaluPreview(naluData)));
    return result;
}

void GstCaptureDecoder::flush()
{
    QMutexLocker lock(&m_mutex);

    if (!m_pipeline || !m_ready) return;

    CaptureDebugScope scope("DEC", "flush", 50);

    gst_element_send_event(m_pipeline,
        gst_event_new_flush_start());
    gst_element_send_event(m_pipeline,
        gst_event_new_flush_stop(TRUE));

    drainAppsink();
    m_pts = 0;
}
