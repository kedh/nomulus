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

package com.google.domain.registry.whois;

import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.testing.DatastoreHelper.createTld;
import static com.google.domain.registry.testing.DatastoreHelper.persistResource;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeHostResource;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.domain.registry.request.RequestModule;
import com.google.domain.registry.testing.AppEngineRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Unit tests for Dagger injection of the whois package. */
@RunWith(JUnit4.class)
public final class WhoisInjectionTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  private final HttpServletRequest req = mock(HttpServletRequest.class);
  private final HttpServletResponse rsp = mock(HttpServletResponse.class);
  private final StringWriter httpOutput = new StringWriter();

  @Before
  public void setUp() throws Exception {
    when(rsp.getWriter()).thenReturn(new PrintWriter(httpOutput));
  }

  @Test
  public void testWhoisServer_injectsAndWorks() throws Exception {
    createTld("lol");
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("ns1.cat.lol\r\n")));
    DaggerWhoisTestComponent.builder()
        .requestModule(new RequestModule(req, rsp))
        .build()
        .whoisServer()
        .run();
    verify(rsp).setStatus(200);
    assertThat(httpOutput.toString()).contains("ns1.cat.lol");
  }

  @Test
  public void testWhoisHttpServer_injectsAndWorks() throws Exception {
    createTld("lol");
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    when(req.getRequestURI()).thenReturn("/whois/ns1.cat.lol");
    DaggerWhoisTestComponent.builder()
        .requestModule(new RequestModule(req, rsp))
        .build()
        .whoisHttpServer()
        .run();
    verify(rsp).setStatus(200);
    assertThat(httpOutput.toString()).contains("ns1.cat.lol");
  }
}