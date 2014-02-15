describe("bars", function () {
	it("", function() {
		// TODO ?
	});

	function bars(element, uiConfig, xScale, yScale, bus) {
		var color = d3.scale.category20();
		var dateFormat = d3.time.format("%d/%m/%Y");
		var valueFormat = function(n) {
			var s = d3.format(",")(n);
			return s.length < 6 ? s : d3.format("s")(n);
		};

		bus.on("dataUpdate", function(update) {
			var barWidth = Math.floor((uiConfig.width / xScale.range.length) - 0.5);
			var layer = element.selectAll(".layer")
				.data(update.dataStacked)
				.enter().append("g")
				.attr("class", "layer")
				.style("fill", function(d, i) { return color(i); });

			layer.selectAll("rect")
				.data(function(d) { return d; })
				.enter().append("rect")
				.attr("x", function(d) { return xScale(d.x); })
				.attr("y", function(d) { return yScale(d.y0 + d.y); })
				.attr("width", barWidth)
				.attr("height", function(d) { return yScale(d.y0) - yScale(d.y0 + d.y); })
				.append("title")
				.text(function (d) {
					return "date: " + dateFormat(d.x) + "\n" +
						"value: " + valueFormat(d.y) + "\n" +
						"category: " + d["category"];
				});
		});
	}
});

describe("x axis", function () {
	it("", function() {
		// TODO ?
	});

	function xAxis(x) {
		return d3.svg.axis().scale(x).orient("bottom");
	}
});

describe("xy scales", function () {
	it("", function() {
		// TODO ?
	});

	function xScale(uiConfig, bus) {
		var timeInterval = d3.time["days"];
		var x = d3.time.scale().nice().rangeRound([0, uiConfig.width]);
		bus.on("dataUpdate", function(update) {
			x.range = timeInterval.range(update.minX, update.maxX);
			x.domain([update.minX, timeInterval.offset(update.maxX, 1)]);
		});
		return x;
	}

	function yScale(uiConfig, bus) {
		var y = d3.scale.linear().range([uiConfig.height, 0]);
		bus.on("dataUpdate", function(update) {
			y.domain([update.minY, update.maxY]);
		});
		return y;
	}
});

describe("bar chart data", function () {
	it("after construction can broadcast update with stacked data", function() {
		var bus = d3.dispatch("dataUpdate");
		var data = stackedData(rawData, bus);
		var received = null;
		bus.on("dataUpdate", function(update) {
			received = update.data;
		});

		data.sendUpdate();

		expect(received.length).toEqual(3);
		expect(received[0][0]["category"]).toEqual("Mee");
		expect(received[1][0]["category"]).toEqual("Ooo");
		expect(received[2][0]["category"]).toEqual("Ggg");
	});

	it("when asked to filter data by categories it will broadcast update", function() {
		var bus = d3.dispatch("dataUpdate");
		var data = stackedData(rawData, bus);
		var received = null;
		bus.on("dataUpdate", function(update) {
			received = update.data;
		});

		data.useCategories(["Mee", "Ggg"]);
		expect(received.length).toEqual(2);
		expect(received[0][0]["category"]).toEqual("Mee");
		expect(received[1][0]["category"]).toEqual("Ggg");
		expect(received[0][0]["y"]).toEqual(1);
		expect(received[1][0]["y"]).toEqual(111);
	});

	var rawData = "\
date,category,value\n\
18/01/2013,Mee,1\n\
19/01/2013,Mee,2\n\
20/01/2013,Mee,3\n\
18/01/2013,Ooo,11\n\
19/01/2013,Ooo,22\n\
20/01/2013,Ooo,33\n\
18/01/2013,Ggg,111\n\
19/01/2013,Ggg,222\n\
20/01/2013,Ggg,333\n\
";

	function stackedData(rawCsv, bus) {
		var dateFormat = d3.time.format("%d/%m/%Y");
		var originalData = d3.csv.parse(rawCsv);
		originalData = d3.nest().key(function(d) { return d["category"]; }).entries(originalData)
			.map(function (entry) {
				return entry.values.map(function (d) {
					return {
						x: dateFormat.parse(d.date),
						y: parseInt(d["value"]),
						category: d["category"],
						y0: 0
					};
				});
			});
		var data;
		var dataStacked;
		var minX;
		var maxX;
		var minY = 0;
		var maxY;
		function updateWith(newData) {
			data = newData;
			dataStacked = d3.layout.stack()(data);
			minX = d3.min(data, function(d) {
				return d3.min(d, function(dd) {
					return dd.x;
				});
			});
			maxX = d3.max(data, function(d) {
				return d3.max(d, function(dd) {
					return dd.x;
				});
			});
			maxY = d3.max(dataStacked, function(layer) {
				return d3.max(layer, function(d) {
					return d.y0 + d.y;
				});
			});
		}
		updateWith(originalData);


		var f = function() {};
		f.sendUpdate = function() {
			bus.dataUpdate({
				data: data,
				dataStacked: dataStacked,
				minX: minX,
				maxX: maxX,
				minY: minY,
				maxY: maxY
			});
		};
		f.useCategories = function(categories) {
			updateWith(
				originalData.map(function(it) {
					return (categories.indexOf(it[0]["category"]) != -1) ? it : null;
				}).filter(function(it) { return it != null; })
			);
			f.sendUpdate();
		};

		return f;
	}

});