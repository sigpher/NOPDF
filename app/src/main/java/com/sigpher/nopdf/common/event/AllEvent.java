package com.sigpher.nopdf.common.event;

/**
 * @author Aaron aaronzzxup@gmail.com
 */
public class AllEvent {

    public boolean isEmpty;
    public String dir;

    public AllEvent() {
    }

    public AllEvent(boolean isEmpty, String dir) {
        this.isEmpty = isEmpty;
        this.dir = dir;
    }
}
