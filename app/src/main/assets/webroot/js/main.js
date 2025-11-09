
let mirrorButton;
let fullButton;
let streamCanvas;
let imageWebsocket = null;
let urlCreator = window.URL || window.webkitURL;
let imageQueue = [];
let freshHandle;
let canvasContext;
let remoteVideoRect;
let mouseDown = false;
function init() {
    mirrorButton = document.getElementById("join");
    fullButton = document.getElementById("fullscreen");
    streamCanvas = document.getElementById("screen");
    canvasContext = streamCanvas.getContext("2d");
    registerEvents();
    registerDrawEvent();
    mouseInit();
}

window.onload = init;

function unInit() {
    unregisterDrawEvent();
    removeEvents();
    unInitWebsocket();
    mirrorButton = null;
    fullButton = null;
    canvasContext = null;
    streamCanvas = null;
    mouseUninit();
}

window.onbeforeunload = unInit;

function registerEvents() {
    mirrorButton.addEventListener("click", onMirrorButtonClick);
    fullButton.addEventListener("click", onFullButtonClick);
    var homeButton = document.getElementById("home");
    homeButton.addEventListener("click", onHomeKeyClick);
    var backButton = document.getElementById("back");
    backButton.addEventListener("click", onBackKeyClick);
}

function removeEvents() {
    mirrorButton.removeEventListener("click", onMirrorButtonClick);
    fullButton.removeEventListener("click", onFullButtonClick);
    var homeButton = document.getElementById("home");
    homeButton.removeEventListener("click", onHomeKeyClick);
    var backButton = document.getElementById("back");
    backButton.removeEventListener("click", onBackKeyClick);
}

function onMirrorButtonClick(event) {
    try {
        unInitWebsocket();
        initWebsocket();
    }
    catch (e) {
        alert(e.name + " : " + e.message);
        document.location.reload();
    }

    var home = document.getElementById("home");
    home.style.visibility = "visible";
    var back = document.getElementById("back");
    back.style.visibility = "visible";
}

function onFullButtonClick(event) {
    toggleFullScreen();
}

function toggleFullScreen() {
    if (streamCanvas == null) return;
    if (streamCanvas.requestFullscreen) {
        streamCanvas.requestFullscreen();
    } else {
        console.log("ok");
    }
}

function onHomeKeyClick(event) {
    var h = codingKey("H");
    sendMouseMessage(h);

}

function onBackKeyClick(event) {
    var h = codingKey("B");
    sendMouseMessage(h);
}

function codingKey(key) {
    return "K," + key + ",0";
}


function initWebsocket() {
    console.log('initWebsocket: init.');

    if (imageWebsocket != null) return;

    const url = 'ws://' + window.location.host + '/screen';
    imageWebsocket = new WebsocketHeartbeatJs({
        url: url,
        pingTimeout: 8000,
        pongTimeout: 8000
    });

    imageWebsocket.onopen = function () {
        console.log('websocket is open!!!!');
    };

    imageWebsocket.onmessage = function (e) {
        prepareImage(e.data);
    };
    imageWebsocket.onreconnect = function () {
        console.log('websocket reconnected once!!');
    }
}

function unInitWebsocket() {
    if (imageWebsocket == null) return;
    imageWebsocket.close();
    imageWebsocket = null;
}

function prepareImage(bytearray) {
    const blob = new Blob([bytearray], { type: "image/jpeg" });
    if (imageQueue.length > 8) {
        console.log("trigger empty blob!!!! ");
        imageQueue = [];
    }
    //console.log("image to queue: ", imageQueue.length);
    imageQueue.push(blob);
}

function registerDrawEvent() {
    if (!freshHandle) {
        freshHandle = setInterval(drawImage, 32);
    }
}

function unregisterDrawEvent() {
    clearInterval(freshHandle);
    freshHandle = null;
}

function drawImage() {
    if (imageQueue.length == 0) {
        return;
    }

    var blob = imageQueue.shift();
    var imageURL = urlCreator.createObjectURL(blob);
    var img = new Image();
    img.onload = function () {
        streamCanvas.width = img.naturalWidth;
        streamCanvas.height = img.naturalHeight;
        urlCreator.revokeObjectURL(imageURL);
        imageURL = null;
        var srcRect = {
            x: 0, y: 0,
            width: img.naturalWidth,
            height: img.naturalHeight
        };
        var dstRect = srcRect;
        //var canvasContext = streamCanvas.getContext("2d");
        try {
            canvasContext.drawImage(img,
                srcRect.x,
                srcRect.y,
                srcRect.width,
                srcRect.height,
                dstRect.x,
                dstRect.y,
                dstRect.width,
                dstRect.height
            );

        } catch (e) {
            console.log("draw image failed", e);
        }
        img = null;
        blob = null;
        //canvasContext = null;
    }
    img.src = imageURL;
}

function mouseInit() {
    remoteVideoRect = document.getElementById('screen');
    remoteVideoRect.addEventListener('mousedown', mouseDownHandler);
    remoteVideoRect.addEventListener('mouseup', mouseUpHandler);
}
function mouseUninit() {
    remoteVideoRect.removeEventListener('mouseup', mouseUpHandler);
    remoteVideoRect.removeEventListener('mousedown', mouseDownHandler);
}

function mouseDownHandler(e) {
    mouseDown = true;
}

function mouseUpHandler(e) {
    if (!mouseDown)
        return;
    mouseDown = false;
    mouseHandler(e, 'up');
}

function getPosition(e) {
    let rect = e.target.getBoundingClientRect();
    let x1 = e.clientX - rect.left;
    let y1 = e.clientY - rect.top;
    let x = 1.0;
    let y = 1.0;

    if (e.target.clientWidth >= e.target.clientHeight) {
        var aspect = 1.0 * e.target.width / e.target.height;
        y = e.target.height / e.target.clientHeight * y1;
        var pictureWidth = e.target.clientHeight * aspect;
        var offset = (e.target.clientWidth - pictureWidth) / 2;
        var xValue = x1 - offset;
        x = xValue * e.target.width / pictureWidth;
    } else {
        x = e.target.width / e.target.clientWidth * x1;
        var aspect = 1.0 * e.target.height / e.target.width;
        var pictureHeight = e.target.clientWidth * aspect;
        var offset = (e.target.clientHeight - pictureHeight) / 2;
        var yValue = y1 - offset;
        y = yValue * e.target.height / pictureHeight;
    }
    // console.log("x=" + x + ", width:" + e.target.clientWidth + "  x1=" + x1 + " rw:" + e.target.width);
    // console.log("y=" + y + ", height:" + e.target.clientHeight + "  y1=" + y1 + " rh:" + e.target.height);
    return { x, y };
}


function mouseHandler(e, action) {
    let position = getPosition(e);
    var msg = codingPosition(position);
    sendMouseMessage(msg);
}

function codingPosition(position) {
    return "M," + position.x + "," + position.y;
}

function sendMouseMessage(message) {
    if (imageWebsocket == null)
        return;

    try {
        var msg = message;
        imageWebsocket.send(msg);
    } catch (e) {
        console.log(e);
    }

}

