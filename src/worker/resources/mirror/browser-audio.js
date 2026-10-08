(() => {
  const token = '__MIRROR_AUDIO_TOKEN__';
  const hex=Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('');
  const frame=[hex.slice(0,8),hex.slice(8,12),hex.slice(12,16),hex.slice(16,20),hex.slice(20)].join('-');
  const Context = window.AudioContext || window.webkitAudioContext;
  if (!Context) return;
  const nativeConnect = AudioNode.prototype.connect;
  const nativeDisconnect = AudioNode.prototype.disconnect;
  const nativeResume = Context.prototype.resume;
  const destinations = new WeakMap();
  const media = new WeakMap();
  let context, mixer, processor, silent, ready;
  const send = value => {
    const message = token + '|' + frame + '|' + value;
    if (window === window.top) chrome.webview.postMessage(message);
    else window.top.postMessage({ mirrorAudio: token, message }, '*');
  };
  if (window === window.top) {
    const post = chrome.webview.postMessage.bind(chrome.webview);
    window.addEventListener('message', event => {
      const data = event.data;
      if (data && data.mirrorAudio === token && typeof data.message === 'string' && data.message.length < 12000) post(data.message);
    });
  }
  const packet = bytes => send(btoa(String.fromCharCode(...new Uint8Array(bytes))));
  const worklet = `class MirrorAudio extends AudioWorkletProcessor {
    constructor(){super();this.data=new Int16Array(1920);this.at=0;this.quiet=0;}
    process(inputs){const channels=inputs[0];if(!channels||!channels[0])return true;
      const left=channels[0],right=channels[1]||left;
      for(let i=0;i<left.length;i++){
        this.data[this.at++]=Math.round(Math.max(-1,Math.min(1,left[i]))*32767);
        this.data[this.at++]=Math.round(Math.max(-1,Math.min(1,right[i]))*32767);
        if(this.at===this.data.length){
          let signal=false;for(let j=0;j<this.data.length;j++)if(this.data[j]){signal=true;break;}
          this.quiet=signal?0:this.quiet+1;
          if(this.quiet<5)this.port.postMessage(this.data.buffer,[this.data.buffer]);
          this.data=new Int16Array(1920);this.at=0;
        }
      }return true;
    }
  } registerProcessor('mirror-audio',MirrorAudio);`;
  function ensure() {
    if (context) return;
    context = new Context({sampleRate:48000,latencyHint:'interactive'});
    mixer = context.createGain();
    silent = context.createGain(); silent.gain.value = 0;
    nativeConnect.call(silent, context.destination);
    ready = (async () => {
      try {
        if (!context.audioWorklet) throw new Error('worklet unavailable');
        const url = URL.createObjectURL(new Blob([worklet], {type:'text/javascript'}));
        try { await context.audioWorklet.addModule(url); } finally { URL.revokeObjectURL(url); }
        processor = new AudioWorkletNode(context, 'mirror-audio', {numberOfInputs:1,numberOfOutputs:1,outputChannelCount:[2]});
        processor.port.onmessage = event => packet(event.data);
      } catch (_) {
        // Some sites disallow blob worklet modules through CSP. Keep their audio usable.
        processor = context.createScriptProcessor(1024,2,2);
        let quiet=0;
        processor.onaudioprocess = event => {
          const left=event.inputBuffer.getChannelData(0), right=event.inputBuffer.numberOfChannels>1?event.inputBuffer.getChannelData(1):left;
          const bytes=new Int16Array(left.length*2);let signal=false;
          for(let i=0;i<left.length;i++){bytes[i*2]=Math.round(Math.max(-1,Math.min(1,left[i]))*32767);bytes[i*2+1]=Math.round(Math.max(-1,Math.min(1,right[i]))*32767);signal ||= bytes[i*2]!==0||bytes[i*2+1]!==0;}
          quiet=signal?0:quiet+1;if(quiet<5)packet(bytes.buffer);
        };
      }
      nativeConnect.call(mixer,processor);nativeConnect.call(processor,silent);
      send('!ready');
    })();
  }
  function resume(){if(context&&context.state==='suspended')nativeResume.call(context).catch(()=>{});}
  function destination(owner) {
    ensure();
    if(owner===context)return mixer;
    let target=destinations.get(owner);
    if(!target){target=owner.createMediaStreamDestination();destinations.set(owner,target);nativeConnect.call(context.createMediaStreamSource(target.stream),mixer);}
    resume();return target;
  }
  AudioNode.prototype.connect = function(target,...args) {
    if(target===this.context.destination&&this.context!==context){nativeConnect.call(this,destination(this.context),...args);return target;}
    return nativeConnect.call(this,target,...args);
  };
  AudioNode.prototype.disconnect = function(...args) {
    if(args[0]===this.context.destination&&destinations.has(this.context))args[0]=destinations.get(this.context);
    return nativeDisconnect.apply(this,args);
  };
  Context.prototype.resume = function(){resume();return nativeResume.call(this);};
  function attach(element) {
    if (!(element instanceof HTMLMediaElement) || media.has(element)) return;
    let source=element.currentSrc||element.src;
    if(!source&&!element.srcObject)return;
    try {
      if(source&&!source.startsWith('blob:')&&!source.startsWith('data:')&&!element.crossOrigin&&new URL(source,location.href).origin!==location.origin){send('!restricted');return;}
      ensure();const node=context.createMediaElementSource(element);media.set(element,node);nativeConnect.call(node,mixer);resume();
    }catch(_){send('!restricted');}
  }
  const play=HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play=function(){attach(this);resume();return play.apply(this,arguments);};
  for(const event of ['play','playing','loadedmetadata'])document.addEventListener(event,e=>attach(e.target),true);
  for(const event of ['pointerdown','keydown'])document.addEventListener(event,resume,true);
})();
