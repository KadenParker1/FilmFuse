package com.pm.filmfuse.group.mapper;

import com.pm.filmfuse.group.dto.ComfortCapDTO;
import com.pm.filmfuse.group.dto.GroupMemberDTO;
import com.pm.filmfuse.group.model.GroupMember;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import java.util.LinkedHashMap;

public final class GroupMapper {

    private GroupMapper() {
    }

    public static ComfortCapDTO toCapDTO(ContentFilter cap) {
        return new ComfortCapDTO(cap.maxCertification(), new LinkedHashMap<>(cap.contentLimits()));
    }

    /** A cap from the API; null fields mean no cap / no limits. */
    public static ContentFilter toCap(ComfortCapDTO dto) {
        return ContentFilter.cap(dto.maxCertification(), dto.contentLimits());
    }

    public static GroupMemberDTO toMemberDTO(GroupMember member, UserSummaryDTO user) {
        return new GroupMemberDTO(user, member.role(), member.status(), member.joinedAt());
    }
}
