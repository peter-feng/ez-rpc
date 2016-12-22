#include <iostream>
#include "HelloService.h"

HelloService::HelloService() {
}

HelloService::~HelloService() {
}

void HelloService::ping() {
	std::cout << "Ping" << std::endl;
}

double HelloService::calculateSquare(double value) {
	return value * value;
}

ExampleData HelloService::enrichExample(ExampleData &exampleData) {
	exampleData.intField += 111;
	exampleData.longField += 22222222;
	exampleData.stringField += " from C++";
	exampleData.planetField = MARS;

	return exampleData;
}
