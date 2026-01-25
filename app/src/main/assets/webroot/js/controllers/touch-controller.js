/**
 * TouchController
 * Handles touch and mouse interactions and translates them to logical coordinates.
 */
export class TouchController {
    constructor(targetElement, onEvent) {
        this.target = targetElement;
        this.onEvent = onEvent;
        this.isMouseDown = false;

        this.registerEvents();
    }

    registerEvents() {
        this.target.addEventListener('mousedown', this.#handleMouseDown.bind(this));
        this.target.addEventListener('mouseup', this.#handleMouseUp.bind(this));
        this.target.addEventListener('mousemove', this.#handleMouseMove.bind(this));
        this.target.addEventListener('touchstart', this.#handleTouchStart.bind(this));
        this.target.addEventListener('touchend', this.#handleTouchEnd.bind(this));
        this.target.addEventListener('touchmove', this.#handleTouchMove.bind(this));
    }

    destroy() {
        // If needed, remove listeners here in future.
    }

    #handleMouseDown(e) {
        this.isMouseDown = true;
        const pos = this.#getPosition(e.clientX, e.clientY);
        if (pos) this.onEvent('D', pos.x, pos.y);
    }

    #handleMouseUp(e) {
        if (!this.isMouseDown) return;
        this.isMouseDown = false;
        const pos = this.#getPosition(e.clientX, e.clientY);
        if (pos) this.onEvent('U', pos.x, pos.y);
    }

    #handleMouseMove(e) {
        if (!this.isMouseDown) return;
        const pos = this.#getPosition(e.clientX, e.clientY);
        if (pos) this.onEvent('M', pos.x, pos.y);
    }

    #handleTouchStart(e) {
        e.preventDefault();
        const touch = e.changedTouches[0];
        const pos = this.#getPosition(touch.clientX, touch.clientY);
        if (pos) this.onEvent('D', pos.x, pos.y);
    }

    #handleTouchEnd(e) {
        e.preventDefault();
        const touch = e.changedTouches[0];
        const pos = this.#getPosition(touch.clientX, touch.clientY);
        if (pos) this.onEvent('U', pos.x, pos.y);
    }

    #handleTouchMove(e) {
        e.preventDefault();
        const touch = e.changedTouches[0];
        const pos = this.#getPosition(touch.clientX, touch.clientY);
        if (pos) this.onEvent('M', pos.x, pos.y);
    }

    #getPosition(clientX, clientY) {
        const rect = this.target.getBoundingClientRect();
        const imageWidth = this.target.width;
        const imageHeight = this.target.height;

        if (imageWidth === 0 || imageHeight === 0) return null;

        const clickX = clientX - rect.left;
        const clickY = clientY - rect.top;

        const displayWidth = rect.width;
        const displayHeight = rect.height;

        const imageAspect = imageWidth / imageHeight;
        const displayAspect = displayWidth / displayHeight;

        let scaledWidth, scaledHeight, offsetX, offsetY;

        if (displayAspect > imageAspect) {
            scaledHeight = displayHeight;
            scaledWidth = scaledHeight * imageAspect;
            offsetX = (displayWidth - scaledWidth) / 2;
            offsetY = 0;
        } else {
            scaledWidth = displayWidth;
            scaledHeight = scaledWidth / imageAspect;
            offsetX = 0;
            offsetY = (displayHeight - scaledHeight) / 2;
        }

        const xOnScaled = clickX - offsetX;
        const yOnScaled = clickY - offsetY;

        const finalX = Math.round((xOnScaled / scaledWidth) * imageWidth);
        const finalY = Math.round((yOnScaled / scaledHeight) * imageHeight);

        if (finalX < 0 || finalX > imageWidth || finalY < 0 || finalY > imageHeight) {
            return null;
        }
        return { x: finalX, y: finalY };
    }
}
