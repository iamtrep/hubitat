/*
  This is a Google Apps Script to detect any specified Gmail and turn on an HE virtual switch via a call to MakerAPI

  Inspired from : https://www.reddit.com/r/GMail/comments/i727l6/ive_made_a_tool_to_filter_gmails_and_trigger_any/

 */
  function Gmail_Trigger() {

  var cloudAPIurl = 'https://cloud.hubitat.com/api/'

  var hubId = 'abcde'                  // get this from "Settings", "Hub Details"
  var makerAPIKey = 'fghij'            // from the MakerAPI instance you plan to use for this automation
  var makerAPIAppId = '100'            // the app ID of the MakerAPI instance
  var virtualSwitchDeviceId = '200'    // the device ID of the virtual switch to be turned on

  var triggerLabel = 'HydroEvenementPointe'   // name of Gmail Label (optional to change)

  //-----------------------------------------------------------//

  console.log('Script Gmail_Trigger() start');

  var label = GmailApp.getUserLabelByName(triggerLabel);

  if (label == null){
    //GmailApp.createLabel(triggerLabel);  // better to go to gmail and create a filter.
    console.error('Label does not exist : %s', triggerLabel);
  } else{
    var threads = label.getThreads();
    if(threads.length > 0){
      console.log('Label found %d emails with label %s',threads.length, triggerLabel);
      console.log('executing commands');
      var response = UrlFetchApp.fetch(cloudAPIurl + hubId + '/apps/' + makerAPIAppId + '/devices/' + virtualSwitchDeviceId + '/on?access_token=' + makerAPIKey);
      console.log('command to hub got response code %d', response.getResponseCode());
    } else {
      console.log('No threads labeled %s',triggerLabel);
    }
    console.log('Removing label from threads');
    label.removeFromThreads(threads);     // comment out this line if removing trigger label from emails not required
  }

    console.log('Script Gmail_Trigger() end');
}

/*

  Typical Hydro email subject line (language specific)

  ❄️ Avis d’événement de pointe | Jeudi 22 décembre 2022

  Create a gmail filter to tag these with triggerLabel

  Sample command :

  https://cloud.hubitat.com/api/[hubId]/apps/[MakerAPIappID]/devices/[Device ID]/[Command]/[Secondary value]?access_token=[MakerAPIkey]

  Instructions:

  0.  Create a virtual switch to be turned on and set up a MakerAPI instance to make it accessible via external http calls
  1.  Save this Script to your account. (Click 'File', 'Save a Copy', close this window and open the copy. Rename it if you like.)
  2.  Paste in your Hub ID, MakerAPI Key, MakerAPI app ID on your hub, and the device ID of the virtual switch to turn on at the top of the file.  Save again.
  3.  Click the 'Run' arrow button and allow permissions. (Advanced etc) Click it again. (It will create the label automatically)
  (Read the script, it can't do anything risky. It simply checks if any mail for that label and makes an http call)
  4.  Click 'Edit, Current project's triggers' above and Add a Trigger for this script to run once every minute, or whenever.
  5.  Set up a Filter in Gmail settings for the (triggering) emails, and set the filter to add the label name. (Default is 'TriggerMail')
  6.  Set up a Rule Machine instance to trigger on the virtual switch turning on and go wild.

 */
