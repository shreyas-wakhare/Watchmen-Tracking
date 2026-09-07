package com.watchmen.tracker

enum class LivenessReason(val stringKey: String) {

    VERIFIED("liveness_verified"),

    NO_FACE("liveness_no_face"),
    NO_FACE_ERROR("liveness_no_face"),

    TOO_SMALL("liveness_move_closer"),

    BLINK_REQUIRED("liveness_blink"),
    BLINK_TOO_LONG("liveness_blink_too_long"),

    HEAD_TURN("liveness_turn_head"),
    HEAD_OK("liveness_head_ok"),
    HEAD_PASSIVE("liveness_head_passive"),

    MOTION_REQUIRED("liveness_motion_required"),
    MOTION_OK("liveness_motion_ok"),

    EYES_OPEN("liveness_eyes_open"),
    EYES_CLOSED("liveness_eyes_closed"),

    HOLD_STEADY("liveness_hold_steady"),

    TRACKING_OK("liveness_tracking_ok"),
    TRACKING_WEAK("liveness_tracking_weak"),

    BUILDING_MOTION("liveness_building_motion"),
    WAITING_BLINK("liveness_waiting_blink"),

    LOW_LIGHT("liveness_low_light"),

    ANALYSIS_ERROR("liveness_analysis_error")
}
