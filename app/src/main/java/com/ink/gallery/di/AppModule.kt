package com.ink.gallery.di

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import com.ink.gallery.data.local.AppDatabase
import com.ink.gallery.data.local.TrashDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "ink_gallery.db").build()

    @Provides
    fun provideTrashDao(database: AppDatabase): TrashDao = database.trashDao()
}
