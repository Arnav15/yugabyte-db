// Copyright (c) YugaByte, Inc.

import React, { Component } from 'react';

import { UniverseDetailContainer } from '../components/universes';

class UniverseDetail extends Component {
  render() {
    return (
      <div>
        <UniverseDetailContainer uuid={this.props.params.uuid}/>
      </div>
    );
  }
}
export default UniverseDetail;
