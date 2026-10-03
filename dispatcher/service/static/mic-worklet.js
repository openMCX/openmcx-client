// Hands microphone samples (at the context's 16 kHz) to the page.
class Mic extends AudioWorkletProcessor {
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (channel) this.port.postMessage(channel.slice(0));
    return true;
  }
}
registerProcessor('mic', Mic);
