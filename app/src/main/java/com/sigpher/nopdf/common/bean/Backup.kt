package com.sigpher.nopdf.common.bean

/**
 * @author aaronzzxup@gmail.com
 * @since 2020/6/14
 */
data class Backup(
        val dirName: String,
        val datas: List<PDF>
)