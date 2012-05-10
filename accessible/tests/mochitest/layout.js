/**
 * Tests if the given child and grand child accessibles at the given point are
 * expected.
 *
 * @param aID            [in] accessible identifier
 * @param aX             [in] x coordinate of the point relative accessible
 * @param aY             [in] y coordinate of the point relative accessible
 * @param aChildID       [in] expected child accessible
 * @param aGrandChildID  [in] expected child accessible
 */
function testChildAtPoint(aID, aX, aY, aChildID, aGrandChildID)
{
  var child = getChildAtPoint(aID, aX, aY, false);
  var expectedChild = getAccessible(aChildID);

  var msg = "Wrong direct child accessible at the point (" + aX + ", " + aY +
    ") of " + prettyName(aID);
  isObject(child, expectedChild, msg);

  var grandChild = getChildAtPoint(aID, aX, aY, true);
  var expectedGrandChild = getAccessible(aGrandChildID);

  msg = "Wrong deepest child accessible at the point (" + aX + ", " + aY +
    ") of " + prettyName(aID);
  isObject(grandChild, expectedGrandChild, msg);
}

/**
 * Test if getChildAtPoint returns the given child and grand child accessibles
 * at coordinates of child accessible (direct and deep hit test).
 */
function hitTest(aContainerID, aChildID, aGrandChildID)
{
  var container = getAccessible(aContainerID);
  var child = getAccessible(aChildID);
  var grandChild = getAccessible(aGrandChildID);

  var [x, y] = getBoundsForDOMElm(child);

  var actualChild = container.getChildAtPoint(x + 1, y + 1);
  isObject(actualChild, child,
           "Wrong direct child of " + prettyName(aContainerID));

  var actualGrandChild = container.getDeepestChildAtPoint(x + 1, y + 1);
  isObject(actualGrandChild, grandChild,
           "Wrong deepest child of " + prettyName(aContainerID));
}

/**
 * Zoom the given document.
 */
function zoomDocument(aDocument, aZoom)
{
  var docShell = aDocument.defaultView.
    QueryInterface(Components.interfaces.nsIInterfaceRequestor).
    getInterface(Components.interfaces.nsIWebNavigation).
    QueryInterface(Components.interfaces.nsIDocShell);
  var docViewer = docShell.contentViewer.
    QueryInterface(Components.interfaces.nsIMarkupDocumentViewer);

  docViewer.fullZoom = aZoom;
}

/**
 * Return child accessible at the given point.
 *
 * @param aIdentifier        [in] accessible identifier
 * @param aX                 [in] x coordinate of the point relative accessible
 * @param aY                 [in] y coordinate of the point relative accessible
 * @param aFindDeepestChild  [in] points whether deepest or nearest child should
 *                           be returned
 * @return                   the child accessible at the given point
 */
function getChildAtPoint(aIdentifier, aX, aY, aFindDeepestChild)
{
  var acc = getAccessible(aIdentifier);
  if (!acc)
    return;

  var [screenX, screenY] = getBoundsForDOMElm(acc.DOMNode);

  var x = screenX + aX;
  var y = screenY + aY;

  try {
    if (aFindDeepestChild)
      return acc.getDeepestChildAtPoint(x, y);
    return acc.getChildAtPoint(x, y);
  } catch (e) {  }

  return null;
}

/**
 * Test the accessible boundaries.
 */
function testBounds(aID, aRect)
{
  var [expectedX, expectedY, expectedWidth, expectedHeight] =
    (aRect != undefined) ? aRect : getBoundsForDOMElm(aID);

  var [x, y, width, height] = getBounds(aID);
  is(x, expectedX, "Wrong x coordinate of " + prettyName(aID));
  is(y, expectedY, "Wrong y coordinate of " + prettyName(aID));
  is(width, expectedWidth, "Wrong width of " + prettyName(aID));
  is(height, expectedHeight, "Wrong height of " + prettyName(aID));
}

/**
 * Return the accessible coordinates and size relative to the screen.
 */
function getBounds(aID)
{
  var accessible = getAccessible(aID);
  var x = {}, y = {}, width = {}, height = {};
  accessible.getBounds(x, y, width, height);
  return [x.value, y.value, width.value, height.value];
}

/**
 * Return DOM node coordinates relative the screen and its size in device
 * pixels.
 */
function getBoundsForDOMElm(aID)
{
  var x = 0, y = 0, width = 0, height = 0;

  var elm = getNode(aID);
  if (elm.localName == "area") {
    var mapName = elm.parentNode.getAttribute("name");
    var selector = "[usemap='#" + mapName + "']";
    var img = elm.ownerDocument.querySelector(selector);

    var areaCoords = elm.coords.split(",");
    var areaX = parseInt(areaCoords[0]);
    var areaY = parseInt(areaCoords[1]);
    var areaWidth = parseInt(areaCoords[2]) - areaX;
    var areaHeight = parseInt(areaCoords[3]) - areaY;

    var rect = img.getBoundingClientRect();
    x = rect.left + areaX;
    y = rect.top + areaY;
    width = areaWidth;
    height = areaHeight;
  }
  else {
    var rect = elm.getBoundingClientRect();
    x = rect.left;
    y = rect.top;
    width = rect.width;
    height = rect.height;
  }

  var elmWindow = elm.ownerDocument.defaultView;
  return CSSToDevicePixels(elmWindow,
                           x + elmWindow.mozInnerScreenX,
                           y + elmWindow.mozInnerScreenY,
                           width,
                           height);
}

function CSSToDevicePixels(aWindow, aX, aY, aWidth, aHeight)
{
  var winUtil = aWindow.
    QueryInterface(Components.interfaces.nsIInterfaceRequestor).
    getInterface(Components.interfaces.nsIDOMWindowUtils);

  var ratio = winUtil.screenPixelsPerCSSPixel;

  // CSS pixels and ratio can be not integer. Device pixels are always integer.
  // Do our best and hope it works.
  return [ Math.round(aX * ratio), Math.round(aY * ratio),
           Math.round(aWidth * ratio), Math.round(aHeight * ratio) ];
}
