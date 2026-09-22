'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const file = 'src/ShellVideo/ShellVideo.js';
let text = fs.readFileSync(file, 'utf8');
if (text.includes("require('./AvSyncSession')")) { console.log('Native session adapter already integrated'); process.exit(0); }
function replace(before, after) { assert.equal(text.split(before).length - 1, 1, 'Native source anchor changed'); text = text.replace(before, after); }
replace("var ERROR = require('../error');", "var ERROR = require('../error');\nvar AvSyncSession = require('./AvSyncSession');");
replace("    'videoScale': null,", "    'videoScale': null,\n    'avSyncV2': null,\n    'avSyncV2Ack': null,");
replace('    var props = {};', `    var props = {};
    var avSyncRevision = 0;
    var avSyncSession = new AvSyncSession({
        getPlayback: function() { return { loaded: props.loaded === true, url: stream ? stream.url : null }; },
        send: function(name, value) { ipc.send(name, value); },
        emit: function(name, value) { if (events && observedProps[name]) events.emit('propChanged', name, value); }
    });`);
replace("    ipc.send('mpv-observe-prop', 'seeking');", "    ipc.send('mpv-observe-prop', 'seeking');\n    ipc.send('mpv-observe-prop', 'avsync');\n    AvSyncSession.properties.forEach(function(name) { ipc.send('mpv-observe-prop', name); });");
replace("    ipc.on('mpv-prop-change', function(args) {\n        switch (args.name) {", "    ipc.on('mpv-prop-change', function(args) {\n        if (destroyed || !args) return;\n        if (args.name === 'avsync') { avSyncSession.observe(args.data); return; }\n        avSyncSession.update(args.name, args.data);\n        switch (args.name) {");
replace('    function getProp(propName) {', "    function getProp(propName) {\n        if (propName === 'avSyncV2') return avSyncSession.snapshot();\n        if (propName === 'avSyncV2Ack') return avSyncSession.lastAck();");
replace('    function setProp(propName, propValue) {', "    function setProp(propName, propValue) {\n        if (['time', 'paused', 'selectedAudioTrackId', 'playbackSpeed'].indexOf(propName) !== -1) avSyncSession.interrupt();");
replace("    function command(commandName, commandArgs) {\n        switch (commandName) {", "    function command(commandName, commandArgs) {\n        switch (commandName) {\n            case 'correctAvSyncV2': { avSyncSession.correct(commandArgs); break; }");
replace('                    waitForMPVVersion.then(function (mpvVersion) {', '                    var loadRevision = avSyncRevision;\n                    avSyncSession.begin(commandArgs.avSyncSessionId);\n                    waitForMPVVersion.then(function (mpvVersion) {\n                        if (destroyed || avSyncRevision !== loadRevision) return;');
replace("            case 'unload': {", "            case 'unload': {\n                avSyncRevision += 1;\n                avSyncSession.close();");
replace("    commands: ['load', 'unload', 'destroy'],", "    commands: ['load', 'unload', 'destroy', 'correctAvSyncV2'],");
fs.writeFileSync(file, text);
console.log('Integrated Core session bound native clock recovery');
