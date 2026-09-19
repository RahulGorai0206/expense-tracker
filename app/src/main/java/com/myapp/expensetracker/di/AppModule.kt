package com.myapp.expensetracker.di

import com.myapp.expensetracker.AppDatabase
import com.myapp.expensetracker.LedgerRepository
import com.myapp.expensetracker.SplitRepository
import com.myapp.expensetracker.viewmodel.AnalyticsViewModel
import com.myapp.expensetracker.viewmodel.HomeViewModel
import com.myapp.expensetracker.viewmodel.LedgerViewModel
import com.myapp.expensetracker.viewmodel.SplitViewModel
import com.myapp.expensetracker.viewmodel.TransactionViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { AppDatabase.getDatabase(androidContext()) }
    single { get<AppDatabase>().transactionDao() }
    single { get<AppDatabase>().monthlyBudgetDao() }
    single { get<AppDatabase>().splitDao() }
    single { get<AppDatabase>().pendingTransactionDao() }
    single { get<AppDatabase>().ledgerDao() }
    single { SplitRepository(get()) }
    single { LedgerRepository(get()) }

    viewModel { HomeViewModel(get(), get()) }
    viewModel { TransactionViewModel(get()) }
    viewModel { AnalyticsViewModel(get()) }
    viewModel { SplitViewModel(get()) }
    viewModel { LedgerViewModel(get()) }
}

