package com.example.equipmentmanagement.common;

import java.util.List;

public class PageResult<T> {

    private long total;
    private int page;
    private int pageSize;
    private List<T> records;

    public PageResult(){}
    public PageResult(long total,int page,int pageSize,List<T> records){
        this.total=total;
        this.page=page;
        this.pageSize=pageSize;
        this.records=records;
    }
    public long getTotal() {
        return total;
    }
    public void setTotal(long total) {
        this.total = total;
    }
    public int getPage() {
        return page;
    }
    public void setPage(int page) {
        this.page = page;
    }
    public int getPageSize() {
        return pageSize;
    }
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
    public List<T> getRecords() {
        return records;
    }
    public void setRecords(List<T> records) {
        this.records = records;
    }


}
