package com.tollbooth.web.dto;

/**
 * Set by the app for each question.
 * GENERAL  = FAQ-like, same answer for everyone ("what's your return policy?")
 * PERSONAL = about this user only ("where is my order?"); never cached (Phase 6)
 */
public enum QuestionCategory {
    GENERAL,
    PERSONAL
}
