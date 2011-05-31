/* vim:set ts=2 sw=2 sts=2 et: */
/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Web Console test suite.
 *
 * The Initial Developer of the Original Code is
 * Mozilla Corporation.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Mihai Sucan <mihai.sucan@gmail.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

const TEST_URI = "http://example.com/browser/toolkit/components/console/hudservice/tests/browser/test-console.html";

function test() {
  addTab(TEST_URI);
  browser.addEventListener("load", function() {
    browser.removeEventListener("load", arguments.callee, true);

    openConsole();
    content.location.reload();
    browser.addEventListener("load", tabLoaded, true);
  }, true);
}

function tabLoaded() {
  browser.removeEventListener("load", tabLoaded, true);

  let hudId = HUDService.getHudIdByWindow(content);
  let HUD = HUDService.hudReferences[hudId];

  HUD.jsterm.execute("document");

  let networkMessage = HUD.outputNode.querySelector(".webconsole-msg-network");
  ok(networkMessage, "found network message");

  let networkLink = networkMessage.querySelector(".webconsole-msg-link");
  ok(networkLink, "found network message link");

  let jstermMessage = HUD.outputNode.querySelector(".webconsole-msg-output");
  ok(jstermMessage, "found output message");

  let popupset = document.getElementById("mainPopupSet");
  ok(popupset, "found #mainPopupSet");

  let popupsShown = 0;
  let hiddenPopups = 0;

  let onpopupshown = function() {
    popupsShown++;
    if (popupsShown == 2) {
      document.removeEventListener("popupshown", onpopupshown, false);

      executeSoon(function() {
        let popups = popupset.querySelectorAll("panel[hudId=" + hudId + "]");
        is(popups.length, 2, "found two popups");

        document.addEventListener("popuphidden", onpopuphidden, false);

        registerCleanupFunction(function() {
          is(hiddenPopups, 2, "correct number of popups hidden");
          if (hiddenPopups != 2) {
            document.removeEventListener("popuphidden", onpopuphidden, false);
          }
        });

        executeSoon(closeConsole);
      });
    }
  };

  let onpopuphidden = function() {
    hiddenPopups++;
    if (hiddenPopups == 2) {
      document.removeEventListener("popuphidden", onpopuphidden, false);

      executeSoon(function() {
        let popups = popupset.querySelectorAll("panel[hudId=" + hudId + "]");
        is(popups.length, 0, "no popups found");

        executeSoon(finishTest);
      });
    }
  };

  document.addEventListener("popupshown", onpopupshown, false);

  registerCleanupFunction(function() {
    is(popupsShown, 2, "correct number of popups shown");
    if (popupsShown != 2) {
      document.removeEventListener("popupshown", onpopupshown, false);
    }
  });

  EventUtils.synthesizeMouse(networkLink, 2, 2, {});
  EventUtils.synthesizeMouse(jstermMessage, 2, 2, {});
}