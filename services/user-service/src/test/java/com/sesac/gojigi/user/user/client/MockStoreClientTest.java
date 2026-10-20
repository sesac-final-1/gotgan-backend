package com.sesac.gojigi.user.user.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TODO(store 개발 완료 시 MockStoreClient 와 함께 삭제) */
class MockStoreClientTest {

    @Test
    @DisplayName("설정한 store_id 만 CLOSED, 나머지는 ACTIVE (storedb 시드와 같음)")
    void getStoreStatus_closedOnlyForConfiguredStores() {
        MockStoreClient client = new MockStoreClient(List.of(2L));

        assertThat(client.getStoreStatus(2L)).isEqualTo(StoreStatus.CLOSED);
        assertThat(client.getStoreStatus(1L)).isEqualTo(StoreStatus.ACTIVE);
        assertThat(new MockStoreClient(List.of()).getStoreStatus(1L)).isEqualTo(StoreStatus.ACTIVE);
    }
}
