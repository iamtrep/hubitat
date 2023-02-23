/*
  This is a Google Apps Script to detect any specified Gmail and turn on an HE virtual switch via a call to MakerAPI

  Inspired from : https://www.reddit.com/r/GMail/comments/i727l6/ive_made_a_tool_to_filter_gmails_and_trigger_any/

 */

function checkForEventTrigger(triggerLabel, remove = true){
  console.log('checkForEventTrigger(%s) start',triggerLabel);
  var label = GmailApp.getUserLabelByName(triggerLabel);

  if (label == null){
    console.error('Label does not exist : %s', label);
  } else{
    var threads = label.getThreads();
    if(threads.length > 0){
      console.log('Label found %d emails with label %s',threads.length, label.getName());
      if (remove){
        console.log('Removing label from threads');
        label.removeFromThreads(threads);
      }
      return true
    } else {
      console.log('No threads labeled %s',label.getName());
    }
  }
  console.log('checkForEventTrigger(%s) end',triggerLabel);
  return false
}

function executeHubCommand(hubId, makerAPIappId, makerAPIKey, switchDeviceId){

  var cloudAPIurl = 'https://cloud.hubitat.com/api/'

  //-----------------------------------------------------------//

  console.log('executeHubCommand() start');
  try {
    console.log('executing command on maker app %d, device %d',makerAPIappId,switchDeviceId)
    var response = UrlFetchApp.fetch(cloudAPIurl + hubId + '/apps/' + makerAPIappId + '/devices/'+ switchDeviceId + '/on?access_token=' + makerAPIKey);
    console.log('hub command got response code %d', response.getResponseCode());
  } catch (e) {
    console.error('UrlFetchApp.fetch() yielded an error: ' + e);
  }
  console.log('executeHubCommand() end');
}


function Gmail_Trigger(){

  var hub1id = 'abcde'
  var hub1makerAPIkey = 'fghij'
  var hub1makerAPIAppId = '123'            // the app ID of the MakerAPI instance
  var hub1virtualSwitchAMDeviceId = '456'    // the device ID of the virtual switch to be turned on for AM events
  var hub1virtualSwitchPMDeviceId = '789'    // the device ID of the virtual switch to be turned on for PM events

  var hub2id = 'klmno'
  var hub2makerAPIkey = 'pqrst'
  var hub2makerAPIAppId = '123'            // the app ID of the MakerAPI instance
  var hub2virtualSwitchAMDeviceId = '456'    // the device ID of the virtual switch to be turned on for AM events
  var hub2virtualSwitchPMDeviceId = '789'    // the device ID of the virtual switch to be turned on for PM events

  var triggerLabelAM = 'HydroEvenementPointeAM'         // name of Gmail Label to trigger on
  var triggerLabelPM = 'HydroEvenementPointePM'

  //-----------------------------------------------------------//

  console.log('Script Gmail_Trigger() start');

  if (checkForEventTrigger(triggerLabelAM)) {
      executeHubCommand(hub1id, hub1makerAPIAppId, hub1makerAPIkey, hub1virtualSwitchAMDeviceId)
      executeHubCommand(hub2id, hub2makerAPIAppId, hub2makerAPIkey, hub2virtualSwitchAMDeviceId)
  }

  if (checkForEventTrigger(triggerLabelPM)) {
      executeHubCommand(hub1id, hub1makerAPIAppId, hub1makerAPIkey, hub1virtualSwitchPMDeviceId)
      executeHubCommand(hub2id, hub2makerAPIAppId, hub2makerAPIkey, hub2virtualSwitchPMDeviceId)
  }

  console.log('Script Gmail_Trigger() end');
}


/*

  Typical Hydro email subject line (language specific):

  ❄️ Avis d’événement de pointe | Jeudi 22 décembre 2022

  Typical body:

  Nous prévoyons que la demande d’électricité sera très élevée demain, ce qui sollicitera fortement l’ensemble du réseau électrique. Un ou des événements de pointe auront donc lieu :

  23 février, de 6 h à 9 h, en matinée.
  23 février, de 16 h à 20 h.

  Create a gmail filter to tag these with triggerLabelAM and/or triggerLabelPM

  Sample command :

  https://cloud.hubitat.com/api/[hubId]/apps/[MakerAPIappID]/devices/[Device ID]/[Command]/[Secondary value]?access_token=[MakerAPIkey]

  Instructions:

  0.  Create a virtual switch to be turned on and set up a MakerAPI instance to make it accessible via external http calls
  1.  Save this Script to your account. (Click 'File', 'Save a Copy', close this window and open the copy. Rename it if you like.)
  2.  Paste in your Hub ID, MakerAPI Key, MakerAPI app ID on your hub, and the device ID of the virtual switch to turn on at the top of the file.  Save again.
  3.  With "GMail_Trigger" selected as the function to run, click the 'Run' arrow button and allow permissions. (Advanced etc) Click it again. (It will create the label automatically)
  (Read the script, it can't do anything risky. It simply checks if any mail for that label and makes an http call)
  4.  Click 'Edit, Current project's triggers' above and Add a Trigger for this script to run once every minute, or whenever.
  5.  Set up a Filter in Gmail settings for the (triggering) emails, and set the filter to add the label name. (Default is 'TriggerMail')
  6.  Set up a Rule Machine instance to trigger on the virtual switch turning on and go wild.

 */
