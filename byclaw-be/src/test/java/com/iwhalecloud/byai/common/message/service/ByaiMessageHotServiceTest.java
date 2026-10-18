package com.iwhalecloud.byai.common.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.iwhalecloud.byai.common.message.entity.ByaiMessage;
import com.iwhalecloud.byai.common.message.entity.ByaiMessageHotDto;
import com.iwhalecloud.byai.manager.mapper.message.ByaiMessageMapper;
import com.iwhalecloud.byai.state.domain.sys.service.SequenceService;

@ExtendWith(MockitoExtension.class)
class ByaiMessageHotServiceTest {

    @Mock
    private SequenceService sequenceService;

    @Mock
    private ByaiMessageMapper byaiMessageMapper;

    @InjectMocks
    private ByaiMessageHotService byaiMessageHotService;

    @Test
    void add_preservesExistingCreateTime() {
        Date firstResponseTime = new Date(1000L);
        ByaiMessageHotDto dto = new ByaiMessageHotDto();
        dto.setMessageId(21L);
        dto.setCreateTime(firstResponseTime);
        when(sequenceService.nextVal()).thenReturn(1L);
        when(byaiMessageMapper.insertBatch(anyList())).thenReturn(1);

        byaiMessageHotService.add(dto);

        ArgumentCaptor<List<ByaiMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(byaiMessageMapper).insertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getCreateTime()).isEqualTo(firstResponseTime);
    }

    @Test
    void add_setsCreateTimeWhenMissing() {
        ByaiMessageHotDto dto = new ByaiMessageHotDto();
        dto.setMessageId(21L);
        when(sequenceService.nextVal()).thenReturn(1L);
        when(byaiMessageMapper.insertBatch(anyList())).thenReturn(1);

        byaiMessageHotService.add(dto);

        ArgumentCaptor<List<ByaiMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(byaiMessageMapper).insertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getCreateTime()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMessageDeletedWhileItsTurnRunsIsNotWrittenBackByALateWrite() {
        org.springframework.data.redis.core.StringRedisTemplate redis =
            org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> values =
            org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        org.springframework.test.util.ReflectionTestUtils.setField(byaiMessageHotService, "redis", redis);
        when(byaiMessageMapper.selectByMessageId(eq(21L))).thenReturn(new ByaiMessage(), (ByaiMessage) null);

        byaiMessageHotService.deleteById(21L);
        verify(values).set(eq("byai:message:deleted:21"), eq("1"), eq(24L), eq(java.util.concurrent.TimeUnit.HOURS));
        when(redis.hasKey("byai:message:deleted:21")).thenReturn(true);
        ByaiMessageHotDto late = new ByaiMessageHotDto();
        late.setMessageId(21L);
        byaiMessageHotService.updateSelective(late);

        verify(byaiMessageMapper, org.mockito.Mockito.never()).insertBatch(anyList());
    }

    @Test
    void updateSelective_setsPrimaryKeyAndCreateTimeWhenMessageDoesNotExist() {
        ByaiMessageHotDto dto = new ByaiMessageHotDto();
        dto.setMessageId(21L);
        when(sequenceService.nextVal()).thenReturn(101L);
        when(byaiMessageMapper.selectByMessageId(eq(21L))).thenReturn(null);
        when(byaiMessageMapper.insertBatch(anyList())).thenReturn(1);

        byaiMessageHotService.updateSelective(dto);

        ArgumentCaptor<List<ByaiMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(byaiMessageMapper).insertBatch(captor.capture());
        ByaiMessage inserted = captor.getValue().get(0);
        assertThat(inserted.getId()).isEqualTo(101L);
        assertThat(inserted.getMessageId()).isEqualTo(21L);
        assertThat(inserted.getCreateTime()).isNotNull();
        assertThat(inserted.getUpdateTime()).isNotNull();
    }
}
