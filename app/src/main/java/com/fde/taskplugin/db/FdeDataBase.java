package com.fde.taskplugin.db;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.fde.taskplugin.db.bean.CollectApp;
import com.fde.taskplugin.db.bean.CompatibleList;
import com.fde.taskplugin.db.bean.RegionInfo;
import com.fde.taskplugin.db.bean.SystemConfig;
import com.fde.taskplugin.db.bean.WifiHistory;
import com.fde.taskplugin.db.dao.CollectAppDao;
import com.fde.taskplugin.db.dao.CompatibleListDao;
import com.fde.taskplugin.db.dao.RegionInfoDao;
import com.fde.taskplugin.db.dao.SystemConfigDao;
import com.fde.taskplugin.db.dao.WifiHistoryDao;

@Database(entities = {CollectApp.class, RegionInfo.class, CompatibleList.class, SystemConfig.class, WifiHistory.class}, version = 1, exportSchema = false)
public abstract class FdeDataBase extends RoomDatabase {

    public abstract CollectAppDao collectAppDao();

    public abstract CompatibleListDao compatibleListDao();

    public abstract RegionInfoDao regionInfoDao();

    public abstract SystemConfigDao systemConfigDao();

    public abstract WifiHistoryDao wifiHistoryDao();


    private static FdeDataBase instance;

    public static synchronized FdeDataBase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context,
                            FdeDataBase.class, "fde_database")
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .addCallback(new Callback() {
                        @Override
                        public void onCreate(@NonNull SupportSQLiteDatabase db) {
                            super.onCreate(db);
                        }

                        @Override
                        public void onDestructiveMigration(@NonNull SupportSQLiteDatabase db) {
                            super.onDestructiveMigration(db);
                        }

                        @Override
                        public void onOpen(@NonNull SupportSQLiteDatabase db) {
                            super.onOpen(db);
                        }
                    })
                    .build();
        }
        return instance;
    }
}
