// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models;

import com.avaje.ebean.*;
import com.avaje.ebean.annotation.DbJson;
import com.avaje.ebean.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;

import com.yugabyte.yw.models.helpers.TaskType;
import com.yugabyte.yw.forms.ITaskParams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.lang.Math.abs;

@Entity
public class Schedule extends Model {
  public static final Logger LOG = LoggerFactory.getLogger(Schedule.class);
  SimpleDateFormat tsFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

  public enum State {
    @EnumValue("Active")
    Active,

    @EnumValue("Paused")
    Paused,

    @EnumValue("Stopped")
    Stopped,
  }

  private static final int MAX_FAIL_COUNT = 3;

  @Id
  public UUID scheduleUUID;
  public UUID getScheduleUUID() { return scheduleUUID; }

  @Column(nullable = false)
  private UUID customerUUID;
  public UUID getCustomerUUID() { return customerUUID; }

  @Column(nullable = false, columnDefinition = "integer default 0")
  private int failureCount;
  public int getFailureCount() { return failureCount; }

  @Column(nullable = false)
  private long frequency;
  public long getFrequency() { return frequency; }

  @Column(nullable = false)
  @DbJson
  private JsonNode taskParams;
  public JsonNode getTaskParams() { return taskParams; }

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private TaskType taskType;
  public TaskType getTaskType() { return taskType; }

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private State status = State.Active;
  public State getStatus() { return status; }

  public static final Find<UUID, Schedule> find = new Find<UUID, Schedule>(){};

  public static Schedule create(UUID customerUUID, ITaskParams params, TaskType taskType, long frequency) {
    Schedule schedule = new Schedule();
    schedule.scheduleUUID = UUID.randomUUID();
    schedule.customerUUID = customerUUID;
    schedule.failureCount = 0;
    schedule.taskType = taskType;
    schedule.taskParams = Json.toJson(params);
    schedule.frequency = frequency;
    schedule.status = State.Active;
    schedule.save();
    return schedule;
  }

  public static Schedule get(UUID scheduleUUID) {
    return find.where().idEq(scheduleUUID).findUnique();
  }

  public static List<Schedule> getAll() {
    return find.findList();
  }

  public static List<Schedule> getAllActiveByCustomerUUID(UUID customerUUID) {
    return find.where().eq("customer_uuid", customerUUID).eq("status", "Active").findList();
  }

  public static List<Schedule> getAllActive() {
    return find.where().eq("status", "Active").findList();
  }

  public void setFailureCount(int count) {
    this.failureCount = count;
    if (count >= MAX_FAIL_COUNT) {
      this.status = State.Paused;
    }
    save();
  }

  public void resetSchedule() {
    this.status = State.Active;
    save();
  }

  public void stopSchedule() {
    this.status = State.Stopped;
    save();
  }
}
