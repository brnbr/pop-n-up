package com.popnup.popnupbackend.domain.popup.exception;

public class PopupNotFoundException extends RuntimeException {

    public PopupNotFoundException(String message) {
        super(message);
    }

    public PopupNotFoundException(Long id) {
        super("해당 팝업스토어를 찾을 수 없습니다. (ID: " + id + ")");
    }
}