package com.popnup.popnupbackend.domain.popup.dto.request;

import com.popnup.popnupbackend.domain.popup.entity.PopupCategory;
import com.popnup.popnupbackend.domain.popup.entity.PopupStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PopupSearchCondition {

    private String keyword;
    private String region;
    private PopupCategory category;
    private PopupStatus status;
}