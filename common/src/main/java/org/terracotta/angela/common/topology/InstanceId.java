/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.angela.common.topology;

import java.io.Serializable;
import java.util.Objects;

/**
 * @author Aurelien Broszniowski
 */

public class InstanceId implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String prefix;
  private final String type;

  public InstanceId(String idPrefix, String type) {
    this.prefix = Objects.requireNonNull(idPrefix).replaceAll("[^a-zA-Z0-9.-]", "_"); ;
    this.type = type;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    InstanceId that = (InstanceId) o;
    return Objects.equals(prefix, that.prefix) &&
        Objects.equals(type, that.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(prefix, type);
  }

  @Override
  public String toString() {
    return String.format("%s-%s", prefix, type);
  }

}
