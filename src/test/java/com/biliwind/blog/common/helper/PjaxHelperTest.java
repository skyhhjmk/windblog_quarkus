package com.biliwind.blog.common.helper;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PjaxHelperTest {

    @Test
    void shouldReturnFalseWhenHttpHeadersIsNull() {
        assertFalse(PjaxHelper.isPjaxRequest((jakarta.ws.rs.core.HttpHeaders) null));
    }

    @Test
    void shouldReturnFalseWhenMultivaluedMapIsNull() {
        assertFalse(PjaxHelper.isPjaxRequest((MultivaluedMap<String, String>) null));
    }

    @Test
    void shouldReturnFalseWhenHeaderValueInMapIsNull() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();

        assertFalse(PjaxHelper.isPjaxRequest(headers));
    }

    @Test
    void shouldReturnFalseWhenHeaderValueInMapIsNotTrue() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add(PjaxHelper.PJAX_HEADER, "false");

        assertFalse(PjaxHelper.isPjaxRequest(headers));
    }

    @Test
    void shouldReturnFalseWhenHeaderValueInMapIsEmpty() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add(PjaxHelper.PJAX_HEADER, "");

        assertFalse(PjaxHelper.isPjaxRequest(headers));
    }

    @Test
    void shouldReturnTrueWhenHeaderValueInMapIsTrue() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add(PjaxHelper.PJAX_HEADER, "true");

        assertTrue(PjaxHelper.isPjaxRequest(headers));
    }

    @Test
    void shouldReturnTrueWhenHeaderValueInMapIsTrueUpperCase() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add(PjaxHelper.PJAX_HEADER, "TRUE");

        assertTrue(PjaxHelper.isPjaxRequest(headers));
    }

    @Test
    void shouldReturnTrueWhenHeaderValueInMapIsTrueMixedCase() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add(PjaxHelper.PJAX_HEADER, "TrUe");

        assertTrue(PjaxHelper.isPjaxRequest(headers));
    }

    @Test
    void shouldReturnFalseWhenHeaderValueInMapIsYes() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add(PjaxHelper.PJAX_HEADER, "yes");

        assertFalse(PjaxHelper.isPjaxRequest(headers));
    }

    @Test
    void shouldReturnFalseWhenHeaderValueInMapIs1() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add(PjaxHelper.PJAX_HEADER, "1");

        assertFalse(PjaxHelper.isPjaxRequest(headers));
    }

    @Test
    void shouldReturnDefaultContainerWhenHttpHeadersIsNull() {
        assertEquals(PjaxHelper.PJAX_CONTAINER_DEFAULT, PjaxHelper.getPjaxContainer(null));
    }

    @Test
    void shouldHaveCorrectDefaultContainerValue() {
        assertEquals("#pjax-container", PjaxHelper.PJAX_CONTAINER_DEFAULT);
    }

    @Test
    void shouldHaveCorrectDefaultContainerId() {
        assertEquals("pjax-container", PjaxHelper.PJAX_CONTAINER_DEFAULT_ID);
    }

    @Test
    void shouldHaveCorrectPjaxHeaderName() {
        assertEquals("X-PJAX", PjaxHelper.PJAX_HEADER);
    }

    @Test
    void shouldHaveCorrectPjaxContainerHeaderName() {
        assertEquals("X-PJAX-Container", PjaxHelper.PJAX_CONTAINER_HEADER);
    }
}