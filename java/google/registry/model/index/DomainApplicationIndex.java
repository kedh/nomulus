// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.model.index;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static com.google.domain.registry.util.CollectionUtils.isNullOrEmpty;
import static com.google.domain.registry.util.DateTimeUtils.latestOf;

import com.google.common.collect.ImmutableSet;
import com.google.domain.registry.model.BackupGroupRoot;
import com.google.domain.registry.model.domain.DomainApplication;
import com.google.domain.registry.util.CollectionUtils;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Ref;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;

import org.joda.time.DateTime;

import java.util.Set;

import javax.annotation.Nullable;

/**
 * Entity for tracking all domain applications with a given fully qualified domain name. Since this
 * resource is always kept up to date as additional domain applications are created, it is never
 * necessary to query them explicitly from Datastore.
 */
@Entity
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
public class DomainApplicationIndex extends BackupGroupRoot {

  @Id
  String fullyQualifiedDomainName;

  /**
   * A set of all domain applications with this fully qualified domain name. Never null or empty.
   */
  Set<Ref<DomainApplication>> references;

  /** Returns a cloned list of all references on this index. */
  public ImmutableSet<Ref<DomainApplication>> getReferences() {
    return ImmutableSet.copyOf(references);
  }

  public String getFullyQualifiedDomainName() {
    return fullyQualifiedDomainName;
  }

  /**
   * Creates a DomainApplicationIndex with the specified list of references.  Only use this method
   * for data migrations.  You probably want {@link #createUpdatedInstance}.
   */
  public static DomainApplicationIndex createWithSpecifiedReferences(
      String fullyQualifiedDomainName,
      ImmutableSet<Ref<DomainApplication>> references) {
    checkArgument(!isNullOrEmpty(fullyQualifiedDomainName),
        "fullyQualifiedDomainName must not be null or empty.");
    checkArgument(!isNullOrEmpty(references), "References must not be null or empty.");
    DomainApplicationIndex instance = new DomainApplicationIndex();
    instance.fullyQualifiedDomainName = fullyQualifiedDomainName;
    instance.references = references;
    return instance;
  }

  public static Key<DomainApplicationIndex> createKey(DomainApplication application) {
    return Key.create(DomainApplicationIndex.class, application.getFullyQualifiedDomainName());
  }

  /**
   * Returns an iterable of all DomainApplications for the given fully qualified domain name that
   * do not have a deletion time before the supplied DateTime.
   */
  public static Iterable<DomainApplication> loadActiveApplicationsByDomainName(
      String fullyQualifiedDomainName, DateTime now) {
    DomainApplicationIndex index = load(fullyQualifiedDomainName);
    if (index == null) {
      return ImmutableSet.of();
    }
    ImmutableSet.Builder<DomainApplication> apps = new ImmutableSet.Builder<>();
    for (DomainApplication app : ofy().load().refs(index.getReferences()).values()) {
      DateTime forwardedNow = latestOf(now, app.getUpdateAutoTimestamp().getTimestamp());
      if (app.getDeletionTime().isAfter(forwardedNow)) {
        apps.add(app.cloneProjectedAtTime(forwardedNow));
      }
    }
    return apps.build();
  }

  /**
   * Returns the DomainApplicationIndex for the given fully qualified domain name. Note that this
   * can return null if there are no domain applications for this fully qualified domain name.
   */
  @Nullable
  public static DomainApplicationIndex load(String fullyQualifiedDomainName) {
    return ofy()
        .load()
        .type(DomainApplicationIndex.class)
        .id(fullyQualifiedDomainName)
        .now();
  }

  /**
   * Saves a new DomainApplicationIndex for this resource or updates the existing one. This is
   * the preferred method for creating an instance of DomainApplicationIndex because this performs
   * the correct merging logic to add the given domain application to an existing index if there
   * is one.
   */
  public static DomainApplicationIndex createUpdatedInstance(DomainApplication application) {
    DomainApplicationIndex existing = load(application.getFullyQualifiedDomainName());
    ImmutableSet<Ref<DomainApplication>> newReferences = CollectionUtils.union(
        (existing == null ? ImmutableSet.<Ref<DomainApplication>>of() : existing.getReferences()),
        Ref.create(application));
    return createWithSpecifiedReferences(application.getFullyQualifiedDomainName(), newReferences);
  }
}