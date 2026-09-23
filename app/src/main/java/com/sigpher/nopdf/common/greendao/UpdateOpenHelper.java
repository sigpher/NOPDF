package com.sigpher.nopdf.common.greendao;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.util.Log;

import com.sigpher.nopdf.common.App;
import com.sigpher.nopdf.common.AppConfig;
import com.sigpher.nopdf.common.DBHelper;

import org.greenrobot.greendao.database.Database;

/**
 * @author Aaron aaronzzxup@gmail.com
 */
public class UpdateOpenHelper extends DaoMaster.OpenHelper {

    public UpdateOpenHelper(Context context, String name) {
        super(context, name);
    }

    public UpdateOpenHelper(Context context, String name, SQLiteDatabase.CursorFactory factory) {
        super(context, name, factory);
    }


    @Override
    public void onUpgrade(Database db, int oldVersion, int newVersion) {
        super.onUpgrade(db, oldVersion, newVersion);
        Log.i("version", oldVersion + "---先前和更新之后的版本---" + newVersion);
        if (oldVersion < newVersion) {
            MigrationHelper.migrate(db, new MigrationHelper.ReCreateAllTableListener() {
                @Override
                public void onCreateAllTables(Database db, boolean ifNotExists) {
                    DaoMaster.createAllTables(db, ifNotExists);
                }

                @Override
                public void onDropAllTables(Database db, boolean ifExists) {
                    DaoMaster.dropAllTables(db, ifExists);
                }
                // 注意：onDropAllTables 会 Drop 掉 DaoMaster 中的全部表，
                // 因此这里必须列出所有 Dao，否则未列出的表会被重建为空表（数据丢失）。
            }, PDFDao.class, CollectionDao.class, RecentPDFDao.class);
        }
    }
}
