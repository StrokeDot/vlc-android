/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

/** Terminal states must release streaming resources; a pending LOAD may replace a session. */
public final class CastSessionPolicy {
    public static final int IGNORE=0, UPDATE=1, END=2, ERROR=3, LAUNCH=4;
    private CastSessionPolicy() {}
    public static int receiverGone(boolean established,boolean launching) {
        return established?END:(launching?IGNORE:LAUNCH);
    }
    public static int media(String state,String reason,boolean loading,boolean hadSession) {
        if("ERROR".equals(reason))return ERROR;
        if(state.isEmpty())return hadSession&&!loading?END:IGNORE;
        if("IDLE".equals(state))return loading?IGNORE:END;
        return UPDATE;
    }
}
