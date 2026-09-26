package com.sigpher.nopdf.common;

import com.sigpher.nopdf.common.bean.Collection;
import com.sigpher.nopdf.common.bean.Cover;
import com.sigpher.nopdf.common.bean.PDF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * @author Aaron aaronzzxup@gmail.com
 */
public final class DataManager {

    private static List<String> pathList;
    private static List<PDF> pdfList;
    private static List<PDF> recentPdfList;
    private static List<Collection> collectionList;

    private static List<Cover> coverList = new ArrayList<>();

    static void init() {
        pdfList = DBHelper.queryAllPDF();
        recentPdfList = DBHelper.queryRecentPDF();
        collectionList = DBHelper.queryAllCollection();
        updatePathList();
        updateCoverList();
    }

    public static void updateAll() {
        DataManager.collectionList.clear();
        DataManager.collectionList.addAll(DBHelper.queryAllCollection());
        DataManager.recentPdfList.clear();
        DataManager.recentPdfList.addAll(DBHelper.queryRecentPDF());
        DataManager.pdfList.clear();
        DataManager.pdfList.addAll(DBHelper.queryAllPDF());
        updatePathList();
        updateCoverList();
    }

    public static void updatePDFs() {
        DataManager.pdfList.clear();
        DataManager.pdfList.addAll(DBHelper.queryAllPDF());
        updatePathList();
        updateCoverList();
    }

    public static void updateRecentPDFs() {
        DataManager.recentPdfList.clear();
        DataManager.recentPdfList.addAll(DBHelper.queryRecentPDF());
    }

    public static void updateCollection() {
        DataManager.collectionList.clear();
        DataManager.collectionList.addAll(DBHelper.queryAllCollection());
        updateCoverList();
    }

    private static void updateCoverList() {
        // 交由纯函数一次分组建好（旧实现按分组逐个全量扫描 + 排序，见 CoverBuilder 注释）。
        List<Cover> covers = CoverBuilder.build(DataManager.pdfList, DataManager.collectionList);
        DataManager.coverList.clear();
        DataManager.coverList.addAll(covers);
    }

    private static void updatePathList() {
        if (pathList == null) {
            pathList = new ArrayList<>();
        }
        pathList.clear();
        for (PDF pdf : pdfList) {
            pathList.add(pdf.getPath());
        }
    }

    public static List<PDF> getRecentPdfList() {
        return recentPdfList;
    }

    public static List<String> getPathList() {
        return pathList;
    }

    public static List<PDF> getPdfList() {
        return pdfList;
    }

    /**
     * 取某个分组下的 PDF，按 position 降序。
     *
     * 注意返回的是**新建的列表**：旧实现复用同一个 static `tempList`，任何调用方只要跨越
     * 下一次调用继续持有它，内容就会被悄悄改写。
     */
    public static List<PDF> getPdfList(String name) {
        List<PDF> list = new ArrayList<>();
        for (PDF pdf : DataManager.pdfList) {
            if (pdf.getDir().equals(name)) {
                list.add(pdf);
            }
        }
        Collections.sort(list, (o1, o2) -> o2.getPosition() - o1.getPosition());
        return list;
    }

    public static List<Collection> getCollectionList() {
        return collectionList;
    }

    public static List<Cover> getCoverList() {
        return coverList;
    }

    private DataManager() {}
}
