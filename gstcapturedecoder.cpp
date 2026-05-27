#include "gstcapturedecoder.h"
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

    // avdec_h264 软解：不占 GPU，不与实时流 d3d11h264dec 竞争
    // h264parse config-interval=-1：自动在每个 IDR 前插入 SPS/PPS
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
        qWarning() << "GstCaptureDecoder: pipeline failed:" << err->message;
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
        "max-buffers", 2,
        "drop", FALSE,
        nullptr);

    GstStateChangeReturn ret = gst_element_set_state(m_pipeline, GST_STATE_PLAYING);
    if (ret == GST_STATE_CHANGE_FAILURE) {
        qWarning() << "GstCaptureDecoder: avdec_h264 pipeline start failed";
        cleanup();
        return false;
    }

    m_ready = true;
    m_pts = 0;
    qDebug() << "GstCaptureDecoder: avdec_h264 pipeline ready";
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

QImage GstCaptureDecoder::pullFrame()
{
    GstSample *sample = gst_app_sink_try_pull_sample(
        GST_APP_SINK(m_appsink), 200 * GST_MSECOND);
    if (!sample) return QImage();

    GstCaps *caps = gst_sample_get_caps(sample);
    GstStructure *s = gst_caps_get_structure(caps, 0);
    int w = 0, h = 0;
    gst_structure_get_int(s, "width", &w);
    gst_structure_get_int(s, "height", &h);

    if (w <= 0 || h <= 0) {
        gst_sample_unref(sample);
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
    return result;
}

QImage GstCaptureDecoder::decodeNalu(const QByteArray &naluData)
{
    QMutexLocker lock(&m_mutex);

    if (naluData.isEmpty()) return QImage();
    if (!ensurePipeline()) return QImage();

    GstBuffer *buffer = gst_buffer_new_allocate(nullptr, naluData.size(), nullptr);
    gst_buffer_fill(buffer, 0, naluData.constData(), naluData.size());

    GST_BUFFER_PTS(buffer) = m_pts;
    GST_BUFFER_DTS(buffer) = m_pts;
    GST_BUFFER_DURATION(buffer) = GST_SECOND / 30;
    m_pts += GST_SECOND / 30;

    GstFlowReturn flowRet = gst_app_src_push_buffer(GST_APP_SRC(m_appsrc), buffer);
    if (flowRet != GST_FLOW_OK) {
        qWarning() << "GstCaptureDecoder: push failed" << flowRet;
        return QImage();
    }

    return pullFrame();
}

void GstCaptureDecoder::flush()
{
    QMutexLocker lock(&m_mutex);

    if (!m_pipeline || !m_ready) return;

    // flush 解码器状态，重新开始解码序列
    gst_element_send_event(m_pipeline,
        gst_event_new_flush_start());
    gst_element_send_event(m_pipeline,
        gst_event_new_flush_stop(TRUE));

    m_pts = 0;
}
