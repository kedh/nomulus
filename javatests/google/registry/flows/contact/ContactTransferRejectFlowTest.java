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

package com.google.domain.registry.flows.contact;

import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.testing.ContactResourceSubject.assertAboutContacts;
import static com.google.domain.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static com.google.domain.registry.testing.DatastoreHelper.deleteResource;
import static com.google.domain.registry.testing.DatastoreHelper.getOnlyPollMessage;
import static com.google.domain.registry.testing.DatastoreHelper.getPollMessages;
import static com.google.domain.registry.testing.DatastoreHelper.persistResource;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.google.domain.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import com.google.domain.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import com.google.domain.registry.flows.ResourceMutateFlow.ResourceToMutateDoesNotExistException;
import com.google.domain.registry.flows.ResourceMutatePendingTransferFlow.NotPendingTransferException;
import com.google.domain.registry.model.contact.ContactAuthInfo;
import com.google.domain.registry.model.contact.ContactResource;
import com.google.domain.registry.model.eppcommon.AuthInfo.PasswordAuth;
import com.google.domain.registry.model.eppcommon.Trid;
import com.google.domain.registry.model.poll.PendingActionNotificationResponse;
import com.google.domain.registry.model.poll.PollMessage;
import com.google.domain.registry.model.reporting.HistoryEntry;
import com.google.domain.registry.model.transfer.TransferResponse;
import com.google.domain.registry.model.transfer.TransferStatus;

import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link ContactTransferRejectFlow}. */
public class ContactTransferRejectFlowTest
    extends ContactTransferFlowTestCase<ContactTransferRejectFlow, ContactResource> {

  @Before
  public void setUp() throws Exception {
    setEppInput("contact_transfer_reject.xml");
    setClientIdForFlow("TheRegistrar");
    setupContactWithPendingTransfer();
    clock.advanceOneMilli();
  }

  private void doSuccessfulTest(String commandFilename, String expectedXmlFilename)
      throws Exception {
    setEppInput(commandFilename);
    // Look in the future and make sure the poll messages for implicit ack are there.
    assertThat(getPollMessages("NewRegistrar", clock.nowUtc().plusMonths(1)))
        .hasSize(1);
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1)))
        .hasSize(1);

    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlowAssertResponse(readFile(expectedXmlFilename));

    // Transfer should have failed. Verify correct fields were set.
    contact = reloadResourceByUniqueId();
    assertAboutContacts().that(contact)
        .hasCurrentSponsorClientId("TheRegistrar").and()
        .hasLastTransferTimeNotEqualTo(clock.nowUtc()).and()
        .hasTransferStatus(TransferStatus.CLIENT_REJECTED).and()
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.CONTACT_TRANSFER_REQUEST,
            HistoryEntry.Type.CONTACT_TRANSFER_REJECT);
    // The poll message (in the future) to the losing registrar for implicit ack should be gone.
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1)))
        .isEmpty();
    // The poll message in the future to the gaining registrar should be gone too, but there
    // should be one at the current time to the gaining registrar.
    PollMessage gainingPollMessage = getOnlyPollMessage("NewRegistrar");
    assertThat(gainingPollMessage.getEventTime()).isEqualTo(clock.nowUtc());
    assertThat(
        Iterables.getOnlyElement(FluentIterable
            .from(gainingPollMessage.getResponseData())
            .filter(TransferResponse.class))
                .getTransferStatus())
                .isEqualTo(TransferStatus.CLIENT_REJECTED);
    PendingActionNotificationResponse panData = Iterables.getOnlyElement(FluentIterable
        .from(gainingPollMessage.getResponseData())
        .filter(PendingActionNotificationResponse.class));
    assertThat(panData.getTrid())
        .isEqualTo(Trid.create("transferClient-trid", "transferServer-trid"));
    assertThat(panData.getActionResult()).isFalse();
    assertNoBillingEvents();
  }

  private void doFailingTest(String commandFilename) throws Exception {
    setEppInput(commandFilename);
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlow();
  }

  @Test
  public void testDryRun() throws Exception {
    setEppInput("contact_transfer_reject.xml");
    dryRunFlowAssertResponse(readFile("contact_transfer_reject_response.xml"));
  }

  @Test
  public void testSuccess() throws Exception {
    doSuccessfulTest("contact_transfer_reject.xml", "contact_transfer_reject_response.xml");
  }

  @Test
  public void testSuccess_domainAuthInfo() throws Exception {
    doSuccessfulTest("contact_transfer_reject_with_authinfo.xml",
        "contact_transfer_reject_response.xml");
  }

  @Test
  public void testFailure_badPassword() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    // Change the contact's password so it does not match the password in the file.
    contact = persistResource(
        contact.asBuilder()
            .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("badpassword")))
            .build());
    doFailingTest("contact_transfer_reject_with_authinfo.xml");
  }

  @Test
  public void testFailure_neverBeenTransferred() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(null);
    doFailingTest("contact_transfer_reject.xml");
  }

  @Test
  public void testFailure_clientApproved() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    doFailingTest("contact_transfer_reject.xml");
  }

 @Test
  public void testFailure_clientRejected() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    doFailingTest("contact_transfer_reject.xml");
  }

 @Test
  public void testFailure_clientCancelled() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    doFailingTest("contact_transfer_reject.xml");
  }

  @Test
  public void testFailure_serverApproved() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    doFailingTest("contact_transfer_reject.xml");
  }

  @Test
  public void testFailure_serverCancelled() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    doFailingTest("contact_transfer_reject.xml");
  }

  @Test
  public void testFailure_gainingClient() throws Exception {
    thrown.expect(ResourceNotOwnedException.class);
    setClientIdForFlow("NewRegistrar");
    doFailingTest("contact_transfer_reject.xml");
  }

  @Test
  public void testFailure_unrelatedClient() throws Exception {
    thrown.expect(ResourceNotOwnedException.class);
    setClientIdForFlow("ClientZ");
    doFailingTest("contact_transfer_reject.xml");
  }

  @Test
  public void testFailure_deletedContact() throws Exception {
    thrown.expect(
        ResourceToMutateDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    contact = persistResource(
        contact.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    doFailingTest("contact_transfer_reject.xml");
  }

  @Test
  public void testFailure_nonexistentContact() throws Exception {
    thrown.expect(
        ResourceToMutateDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    deleteResource(contact);
    doFailingTest("contact_transfer_reject.xml");
  }
}